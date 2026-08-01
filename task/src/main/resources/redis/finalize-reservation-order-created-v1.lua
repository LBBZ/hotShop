-- Idempotently advances one Redis Reservation after the MySQL Order commits.
-- KEYS: 1 Reservation hash
-- ARGV: reservationNo, activityId, userId, productId, quantity, unitPrice,
--       requestFingerprint, orderId, completedAtMs

local function result(code)
    return {code}
end

local valueType = redis.call('TYPE', KEYS[1])['ok']
if valueType ~= 'hash' then
    return result(valueType == 'none' and 'MISSING' or 'INVALID_TYPE')
end

local expected = {
    {'schemaVersion', '1'},
    {'reservationNo', ARGV[1]},
    {'activityId', ARGV[2]},
    {'userId', ARGV[3]},
    {'productId', ARGV[4]},
    {'quantity', ARGV[5]},
    {'unitPrice', ARGV[6]},
    {'currency', 'CNY'},
    {'requestFingerprint', ARGV[7]}
}
for _, field in ipairs(expected) do
    if redis.call('HGET', KEYS[1], field[1]) ~= field[2] then
        return result('FACT_CONFLICT')
    end
end

local status = redis.call('HGET', KEYS[1], 'status')
local currentOrderId = redis.call('HGET', KEYS[1], 'orderId')
if status == 'ORDER_CREATED' then
    return result(currentOrderId == ARGV[8] and 'IDEMPOTENT' or 'ORDER_CONFLICT')
end
if status == 'COMPENSATED' or status == 'COMPENSATING' then
    return result('COMPENSATION_CONFLICT')
end
if status ~= 'RESERVED' then
    return result('STATUS_CONFLICT')
end

redis.call(
    'HSET', KEYS[1],
    'status', 'ORDER_CREATED',
    'orderId', ARGV[8],
    'orderCreatedAtMs', ARGV[9]
)
return result('FINALIZED')
