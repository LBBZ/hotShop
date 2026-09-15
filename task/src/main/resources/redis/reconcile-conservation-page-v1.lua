-- One atomic, restartable conservation page. No full-history reads.
-- KEYS: metadata, stock, stream, persistent reconciliation checkpoint.
-- ARGV: maximum records (hard XRANGE COUNT), reservation-key prefix.
-- Only effective-state-changing writers advance inventoryRevision. Finalizing
-- RESERVED -> ORDER_CREATED does not change effective quantity.
local meta, stock, stream, checkpoint = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local revision = redis.call('HGET', meta, 'inventoryRevision') or '0'
local epoch = redis.call('HGET', meta, 'databaseVersion') or '0'
local fence = epoch .. ':' .. revision
local previous = redis.call('HGET', checkpoint, 'fence')
local cursor = redis.call('HGET', checkpoint, 'cursor') or '0-0'
local total = tonumber(redis.call('HGET', checkpoint, 'quantity') or '0')
local invalid = tonumber(redis.call('HGET', checkpoint, 'invalid') or '0')
local restarted = '0'
if previous ~= fence then
    if previous then
        redis.call('HINCRBY', checkpoint, 'restarts', 1)
        restarted = '1'
    end
    cursor, total, invalid = '0-0', 0, 0
end
local rows = redis.call('XRANGE', stream, '(' .. cursor, '+', 'COUNT', ARGV[1])
for _, row in ipairs(rows) do
    local fields = {}
    for i = 1, #row[2], 2 do fields[row[2][i]] = row[2][i + 1] end
    local no = fields.reservationNo
    local quantity = tonumber(fields.quantity)
    if not no or #no > 64 or not quantity or quantity <= 0 then
        invalid = invalid + 1
    else
        local fact = redis.call('HMGET', ARGV[2] .. no,
            'status', 'quantity', 'stockRestored', 'compensationId', 'reservationNo')
        if not fact[1] or tonumber(fact[2]) ~= quantity or fact[5] ~= no then
            invalid = invalid + 1
        elseif fact[1] == 'RESERVED' or fact[1] == 'ORDER_CREATED' or fact[1] == 'COMPENSATING' then
            total = total + quantity
        elseif fact[1] == 'COMPENSATED' then
            if fact[3] ~= '1' or not fact[4] then invalid = invalid + 1 end
        elseif fact[1] ~= 'PAYMENT_EXPIRED' then
            invalid = invalid + 1
        end
    end
    cursor = row[1]
end
-- XLEN is O(1). A page ending exactly at COUNT completes on the next empty page.
-- Finishing requires the same writer fence throughout the whole historical scan.
local complete = #rows < tonumber(ARGV[1])
local state = complete and 'COMPLETE' or 'IN_PROGRESS'
local initial = redis.call('HGET', meta, 'initialAvailableStock') or ''
local current = redis.call('GET', stock) or ''
redis.call('HSET', checkpoint, 'fence', fence, 'cursor', complete and '0-0' or cursor,
    'quantity', complete and '0' or tostring(total),
    'invalid', complete and '0' or tostring(invalid), 'state', state,
    'lastScanned', tostring(#rows))
if complete then
    redis.call('HINCRBY', checkpoint, 'completedScans', 1)
    redis.call('HSET', checkpoint, 'lastCompletedQuantity', tostring(total),
        'lastCompletedInvalid', tostring(invalid), 'lastCompletedFence', fence)
end
return {state, tostring(#rows), tostring(total), initial, current,
    tostring(invalid), fence, restarted}
