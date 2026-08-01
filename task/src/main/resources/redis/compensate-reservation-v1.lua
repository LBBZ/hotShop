-- Atomically compensates one accepted Reservation exactly once.
-- KEYS: 1 Reservation hash, 2 available stock string, 3 User reservation string
-- ARGV: reservationNo, activityId, userId, quantity, compensationId,
--       reasonCode, compensatedAtMs

local function result(code, stock)
    return {code, stock or ''}
end

local function valid_type(key, expected, allowNone)
    local valueType = redis.call('TYPE', key)['ok']
    return valueType == expected or (allowNone and valueType == 'none')
end

if not valid_type(KEYS[1], 'hash', false)
        or not valid_type(KEYS[2], 'string', false)
        or not valid_type(KEYS[3], 'string', true) then
    return result('INVALID_TYPE')
end

local expected = {
    {'schemaVersion', '1'},
    {'reservationNo', ARGV[1]},
    {'activityId', ARGV[2]},
    {'userId', ARGV[3]},
    {'quantity', ARGV[4]}
}
for _, field in ipairs(expected) do
    if redis.call('HGET', KEYS[1], field[1]) ~= field[2] then
        return result('FACT_CONFLICT')
    end
end

local status = redis.call('HGET', KEYS[1], 'status')
local currentCompensationId = redis.call('HGET', KEYS[1], 'compensationId')
if status == 'COMPENSATED' then
    if currentCompensationId == ARGV[5]
            and redis.call('HGET', KEYS[1], 'stockRestored') == '1' then
        return result('IDEMPOTENT', redis.call('GET', KEYS[2]))
    end
    return result('COMPENSATION_CONFLICT')
end
if status == 'ORDER_CREATED' then
    return result('ORDER_CONFLICT')
end
if status ~= 'RESERVED' and status ~= 'COMPENSATING' then
    return result('STATUS_CONFLICT')
end
if currentCompensationId and currentCompensationId ~= ARGV[5] then
    return result('COMPENSATION_CONFLICT')
end
if redis.call('GET', KEYS[3]) ~= ARGV[1] then
    return result('USER_SLOT_CONFLICT')
end

local stockRaw = redis.call('GET', KEYS[2])
if not stockRaw or not string.match(stockRaw, '^%d+$') then
    return result('STOCK_INVALID')
end
local quantity = tonumber(ARGV[4])
if not quantity or quantity <= 0 then
    return result('FACT_CONFLICT')
end

-- Allocate every final hash field before touching stock. If Redis is out of
-- memory, the script remains at a legal COMPENSATING intent and can be retried.
local prepare = redis.pcall(
    'HSET', KEYS[1],
    'status', 'COMPENSATING',
    'compensationId', ARGV[5],
    'reasonCode', ARGV[6],
    'compensatedAtMs', ARGV[7],
    'stockRestored', '0'
)
if type(prepare) == 'table' and prepare['err'] then
    return result('STORAGE_ERROR')
end

local newStock = redis.call('INCRBY', KEYS[2], quantity)
redis.call('DEL', KEYS[3])
redis.call(
    'HSET', KEYS[1],
    'status', 'COMPENSATED',
    'compensationId', ARGV[5],
    'reasonCode', ARGV[6],
    'compensatedAtMs', ARGV[7],
    'stockRestored', '1'
)
return result('COMPENSATED', tostring(newStock))
