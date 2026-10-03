-- Bounded, atomic observation of one loaded offer. No business writes.
-- KEYS: metadata, stock, stream.
-- ARGV: activityId, reservation-key prefix, user-key prefix, maximum events.
local function type_is(key, expected, allowNone)
    local kind = redis.call('TYPE', key).ok
    return kind == expected or (allowNone and kind == 'none')
end
local function number(value)
    if not value or not string.match(value, '^%d+$') then return nil end
    return tonumber(value)
end
if not type_is(KEYS[1], 'hash', false) or not type_is(KEYS[2], 'string', false)
        or not type_is(KEYS[3], 'stream', true) then
    return {'INVALID', '', '', '', '0', '0', '0', '0'}
end
local version = redis.call('HGET', KEYS[1], 'databaseVersion') or ''
local stock = redis.call('GET', KEYS[2]) or ''
local initial = redis.call('HGET', KEYS[1], 'initialAvailableStock') or ''
local count = redis.call('XLEN', KEYS[3])
local records, total, effective = 0, 0, 0
local function result(state)
    return {state, version, stock, initial, tostring(count),
        tostring(records), tostring(total), tostring(effective)}
end
if not number(version) or not number(stock) or not number(initial)
        or redis.call('HGET', KEYS[1], 'activityId') ~= ARGV[1] then
    return result('INVALID')
end
local maximum = tonumber(ARGV[4])
if count > maximum then return result('LIMIT_EXCEEDED') end
local entries = redis.call('XRANGE', KEYS[3], '-', '+', 'COUNT', maximum)
local seen, valid = {}, true
for _, entry in ipairs(entries) do
    local event = {}
    for i = 1, #entry[2], 2 do event[entry[2][i]] = entry[2][i + 1] end
    local no, user = event.reservationNo, event.userId
    local quantity = number(event.quantity)
    if not no or #no == 0 or #no > 64 or not number(user)
            or not quantity or quantity <= 0 or event.activityId ~= ARGV[1]
            or event.schemaVersion ~= '1' or event.eventType ~= 'RESERVATION_ACCEPTED' then
        valid = false
    else
        local reservation = ARGV[2] .. no
        local userKey = ARGV[3] .. user .. ':reservation'
        if not type_is(reservation, 'hash', false) or not type_is(userKey, 'string', true) then
            valid = false
        else
            -- Every delivery must agree with its reservation; count business
            -- reservations once even if an event was duplicated in the Stream.
            for _, field in ipairs({'schemaVersion', 'reservationNo', 'activityId', 'userId', 'productId',
                    'quantity', 'unitPrice', 'currency', 'activityVersion',
                    'requestFingerprint', 'idempotencyKeyHash'}) do
                if not event[field] or redis.call('HGET', reservation, field) ~= event[field] then
                    valid = false
                end
            end
            if not seen[no] then
                seen[no] = true
                records = records + 1
                total = total + quantity
                local status = redis.call('HGET', reservation, 'status')
                local slot = redis.call('GET', userKey)
                if status == 'RESERVED' or status == 'ORDER_CREATED' or status == 'COMPENSATING' then
                    effective = effective + quantity
                    if slot ~= no then valid = false end
                    if status == 'ORDER_CREATED' then
                        local order = redis.call('HGET', reservation, 'orderId')
                        if not order or order == '' then valid = false end
                    end
                elseif status == 'COMPENSATED' then
                    local compensation = redis.call('HGET', reservation, 'compensationId')
                    if redis.call('HGET', reservation, 'stockRestored') ~= '1'
                            or not compensation or compensation == '' or slot == no then valid = false end
                elseif status == 'PAYMENT_EXPIRED' then
                    local order = redis.call('HGET', reservation, 'orderId')
                    if not order or order == '' or slot == no then valid = false end
                else
                    valid = false
                end
            end
        end
    end
end
return result(valid and 'COMPLETE' or 'INVALID')
