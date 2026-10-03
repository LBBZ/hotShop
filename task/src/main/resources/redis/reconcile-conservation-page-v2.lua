-- Bounded conservation cycles. Never interpret a moving snapshot as agreement.
-- KEYS: metadata, stock, stream, checkpoint, per-cycle seen hash (same slot).
-- ARGV: maximum records per page, reservation-key prefix, maximum distinct facts.
-- Writers are unchanged: inventoryRevision advances before effective-state changes.
local meta, stock, stream, checkpoint, seen = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local pageSize, maximumSeen = tonumber(ARGV[1]), tonumber(ARGV[3])
local observedFence = (redis.call('HGET', meta, 'databaseVersion') or '0') .. ':'
    .. (redis.call('HGET', meta, 'inventoryRevision') or '0')
local fence = redis.call('HGET', checkpoint, 'fence')
local cursor = redis.call('HGET', checkpoint, 'cursor') or '0-0'
local upper = redis.call('HGET', checkpoint, 'upperBound')
local total = tonumber(redis.call('HGET', checkpoint, 'quantity') or '0')
local invalid = tonumber(redis.call('HGET', checkpoint, 'invalid') or '0')
local changed = redis.call('HGET', checkpoint, 'changed') == '1'
local priorState = redis.call('HGET', checkpoint, 'state')
local restarted = '0'
-- A legacy / incomplete checkpoint cannot reuse its aggregate. Detach a large
-- old seen table without synchronously reclaiming all its entries.
if redis.call('HGET', checkpoint, 'schemaVersion') ~= '3' or priorState ~= 'IN_PROGRESS'
        or not upper or redis.call('EXISTS', seen) == 0 then
    if fence and priorState == 'IN_PROGRESS' then
        redis.call('HINCRBY', checkpoint, 'restarts', 1)
        restarted = '1'
    end
    cursor, total, invalid, changed, fence = '0-0', 0, 0, false, observedFence
    local tail = redis.call('XREVRANGE', stream, '+', '-', 'COUNT', 1)
    upper = #tail == 0 and '0-0' or tail[1][1]
    redis.call('UNLINK', seen)
    redis.call('HSET', seen, 'initialized', '1')
elseif fence ~= observedFence then
    -- Keep advancing to the captured tail, even if writers keep changing the
    -- fence. This cycle will end INCONCLUSIVE, never reset to the first page.
    changed = true
end

local seenCount = redis.call('HLEN', seen) - 1
local limited = seenCount > maximumSeen
local rows = {}
if not limited then
    rows = redis.call('XRANGE', stream, '(' .. cursor, upper, 'COUNT', pageSize)
end
for _, row in ipairs(rows) do
    local fields = {}
    for i = 1, #row[2], 2 do fields[row[2][i]] = row[2][i + 1] end
    local no = fields.reservationNo
    local quantity = tonumber(fields.quantity)
    if not no or #no == 0 or #no > 64 or not quantity or quantity <= 0 then
        invalid = invalid + 1
    else
        local fact = redis.call('HMGET', ARGV[2] .. no,
            'status', 'quantity', 'stockRestored', 'compensationId', 'reservationNo')
        if not fact[1] or tonumber(fact[2]) ~= quantity or fact[5] ~= no then
            invalid = invalid + 1
        elseif fact[1] == 'RESERVED' or fact[1] == 'ORDER_CREATED' or fact[1] == 'COMPENSATING' then
            local member = 'reservation:' .. no
            -- Repeated deliveries still validate above, but consume neither a
            -- second dedup slot nor a second inventory quantity.
            if redis.call('HEXISTS', seen, member) == 0 then
                if seenCount >= maximumSeen then
                    limited = true
                    break
                end
                redis.call('HSET', seen, member, '1')
                seenCount = seenCount + 1
                total = total + quantity
            end
        elseif fact[1] == 'COMPENSATED' then
            if fact[3] ~= '1' or not fact[4] then invalid = invalid + 1 end
        elseif fact[1] ~= 'PAYMENT_EXPIRED' then
            invalid = invalid + 1
        end
    end
    cursor = row[1]
end

-- As in v1, a page ending exactly at COUNT uses one final empty page. The fixed
-- upper bound prevents fresh appends from extending this cycle indefinitely.
local ended = limited or #rows < pageSize
local reason = limited and 'RESERVATION_LIMIT' or (changed and 'WRITES_DURING_SCAN' or '')
local state = ended and (reason == '' and 'COMPLETE' or 'INCONCLUSIVE') or 'IN_PROGRESS'
local initial = redis.call('HGET', meta, 'initialAvailableStock') or ''
local current = redis.call('GET', stock) or ''
redis.call('HSET', checkpoint, 'schemaVersion', '3', 'fence', fence, 'observedFence', observedFence,
    'upperBound', upper, 'cursor', ended and '0-0' or cursor,
    'quantity', ended and '0' or tostring(total), 'invalid', ended and '0' or tostring(invalid),
    'changed', changed and '1' or '0', 'state', state, 'reason', reason,
    'lastScanned', tostring(#rows), 'lastSeenCount', tostring(seenCount))
if ended then
    redis.call('HINCRBY', checkpoint, 'finishedCycles', 1)
    if state == 'COMPLETE' then
        redis.call('HINCRBY', checkpoint, 'completedScans', 1)
        redis.call('HSET', checkpoint, 'lastCompletedQuantity', tostring(total),
            'lastCompletedInvalid', tostring(invalid), 'lastCompletedFence', fence)
    else
        redis.call('HINCRBY', checkpoint, 'inconclusiveScans', 1)
    end
    redis.call('UNLINK', seen)
else
    -- Idle workers must not retain per-reservation scan metadata forever.
    -- A missing table always restarts the aggregate on the next visit.
    redis.call('EXPIRE', seen, 86400)
end
return {state, tostring(#rows), tostring(total), initial, current,
    tostring(invalid), fence, restarted, reason, tostring(seenCount), upper}
