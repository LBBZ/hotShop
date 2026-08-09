-- HotShop flash-sale reservation v1.
-- KEYS:
-- 1 activity metadata hash
-- 2 available stock string
-- 3 effective User reservation string
-- 4 global User/idempotency hash
-- 5 Reservation hash
-- 6 per-activity Stream
--
-- ARGV:
-- 1 activityId, 2 userId, 3 quantity, 4 request fingerprint,
-- 5 reservationNo, 6 eventId, 7 requestId,
-- 8 reservation TTL seconds, 9 idempotency TTL seconds, 10 idempotency key hash,
-- 11 traceparent, 12 tracestate

local function result(code, reservationNo, requestId, streamId)
    return {code, reservationNo or '', requestId or '', streamId or ''}
end

local function valid_type(key, expected, allowNone)
    local valueType = redis.call('TYPE', key)['ok']
    return valueType == expected or (allowNone and valueType == 'none')
end

if not valid_type(KEYS[1], 'hash', true)
        or not valid_type(KEYS[2], 'string', true)
        or not valid_type(KEYS[3], 'string', true)
        or not valid_type(KEYS[4], 'hash', true)
        or not valid_type(KEYS[5], 'hash', true)
        or not valid_type(KEYS[6], 'stream', true) then
    return result('INTERNAL_STATE_INVALID')
end

if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
    return result('ACTIVITY_NOT_FOUND')
end

local schemaVersion = redis.call('HGET', KEYS[1], 'schemaVersion')
local activityId = redis.call('HGET', KEYS[1], 'activityId')
local productId = redis.call('HGET', KEYS[1], 'productId')
local unitPrice = redis.call('HGET', KEYS[1], 'unitPrice')
local activityVersion = redis.call('HGET', KEYS[1], 'databaseVersion')
local perUserLimitRaw = redis.call('HGET', KEYS[1], 'perUserLimit')
local status = redis.call('HGET', KEYS[1], 'status')
local startsAtRaw = redis.call('HGET', KEYS[1], 'startsAtMs')
local endsAtRaw = redis.call('HGET', KEYS[1], 'endsAtMs')

if schemaVersion ~= '1' or activityId ~= ARGV[1]
        or not productId or not string.match(productId, '^%d+$')
        or not unitPrice
        or not activityVersion or not string.match(activityVersion, '^%d+$')
        or not perUserLimitRaw or not string.match(perUserLimitRaw, '^%d+$')
        or not status
        or not startsAtRaw or not string.match(startsAtRaw, '^%d+$')
        or not endsAtRaw or not string.match(endsAtRaw, '^%d+$') then
    return result('INTERNAL_STATE_INVALID')
end

local quantityRaw = ARGV[3]
if not quantityRaw or not string.match(quantityRaw, '^%d+$') then
    return result('INVALID_QUANTITY')
end
local quantity = tonumber(quantityRaw)
local perUserLimit = tonumber(perUserLimitRaw)
if quantity == nil or quantity <= 0 or perUserLimit == nil or perUserLimit <= 0 then
    return result('INVALID_QUANTITY')
end

if redis.call('EXISTS', KEYS[4]) == 1 then
    local savedFingerprint = redis.call('HGET', KEYS[4], 'fingerprint')
    local savedReservationNo = redis.call('HGET', KEYS[4], 'reservationNo')
    local savedRequestId = redis.call('HGET', KEYS[4], 'requestId')
    if not savedFingerprint or not savedReservationNo or not savedRequestId then
        return result('INTERNAL_STATE_INVALID')
    end
    if savedFingerprint ~= ARGV[4] then
        return result('IDEMPOTENCY_CONFLICT')
    end
    return result('IDEMPOTENT_REPLAY', savedReservationNo, savedRequestId)
end

if quantity > perUserLimit then
    return result('USER_LIMIT_REACHED')
end

local time = redis.call('TIME')
local nowMs = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local startsAtMs = tonumber(startsAtRaw)
local endsAtMs = tonumber(endsAtRaw)
if nowMs < startsAtMs then
    return result('ACTIVITY_NOT_STARTED')
end
if nowMs >= endsAtMs then
    return result('ACTIVITY_ENDED')
end
if status ~= 'ACTIVE' then
    return result('ACTIVITY_NOT_ACTIVE')
end

if redis.call('EXISTS', KEYS[3]) == 1 then
    return result('USER_LIMIT_REACHED')
end
if redis.call('EXISTS', KEYS[5]) == 1 then
    return result('INTERNAL_STATE_INVALID')
end

local stockRaw = redis.call('GET', KEYS[2])
if not stockRaw or not string.match(stockRaw, '^%d+$') then
    return result('INTERNAL_STATE_INVALID')
end
local stock = tonumber(stockRaw)
if stock == nil or stock < 0 then
    return result('INTERNAL_STATE_INVALID')
end
if stock < quantity then
    return result('SOLD_OUT')
end

local reservationRetention = tonumber(ARGV[8])
local idempotencyTtl = tonumber(ARGV[9])
if reservationRetention == nil or reservationRetention <= 0
        or idempotencyTtl == nil or idempotencyTtl <= 0 then
    return result('INTERNAL_STATE_INVALID')
end
local reservationTtl = math.floor((endsAtMs - nowMs + 999) / 1000) + reservationRetention

local reservationWrite = redis.pcall(
    'HSET', KEYS[5],
    'schemaVersion', '1',
    'reservationNo', ARGV[5],
    'activityId', ARGV[1],
    'userId', ARGV[2],
    'productId', productId,
    'quantity', quantityRaw,
    'unitPrice', unitPrice,
    'currency', 'CNY',
    'status', 'RESERVED',
    'requestId', ARGV[7],
    'traceparent', ARGV[11],
    'tracestate', ARGV[12],
    'activityVersion', activityVersion,
    'idempotencyKeyHash', ARGV[10],
    'requestFingerprint', ARGV[4],
    'reservedAtMs', tostring(nowMs)
)
if type(reservationWrite) == 'table' and reservationWrite['err'] then
    return result('INTERNAL_STATE_INVALID')
end
local reservationExpire = redis.pcall('EXPIRE', KEYS[5], reservationTtl)
if type(reservationExpire) == 'table' and reservationExpire['err'] then
    redis.pcall('DEL', KEYS[5])
    return result('INTERNAL_STATE_INVALID')
end

local userWrite = redis.pcall('SET', KEYS[3], ARGV[5], 'NX', 'EX', reservationTtl)
if type(userWrite) == 'table' and userWrite['err'] then
    redis.pcall('DEL', KEYS[5])
    return result('INTERNAL_STATE_INVALID')
end
if not userWrite then
    redis.pcall('DEL', KEYS[5])
    return result('USER_LIMIT_REACHED')
end

local idempotencyWrite = redis.pcall(
    'HSET', KEYS[4],
    'schemaVersion', '1',
    'fingerprint', ARGV[4],
    'reservationNo', ARGV[5],
    'activityId', ARGV[1],
    'quantity', quantityRaw,
    'requestId', ARGV[7],
    'status', 'RESERVED'
)
if type(idempotencyWrite) == 'table' and idempotencyWrite['err'] then
    redis.pcall('DEL', KEYS[3])
    redis.pcall('DEL', KEYS[5])
    return result('INTERNAL_STATE_INVALID')
end
local idempotencyExpire = redis.pcall('EXPIRE', KEYS[4], idempotencyTtl)
if type(idempotencyExpire) == 'table' and idempotencyExpire['err'] then
    redis.pcall('DEL', KEYS[4])
    redis.pcall('DEL', KEYS[3])
    redis.pcall('DEL', KEYS[5])
    return result('INTERNAL_STATE_INVALID')
end

local streamWrite = redis.pcall(
    'XADD', KEYS[6], '*',
    'schemaVersion', '1',
    'eventType', 'RESERVATION_ACCEPTED',
    'eventId', ARGV[6],
    'reservationNo', ARGV[5],
    'activityId', ARGV[1],
    'userId', ARGV[2],
    'productId', productId,
    'quantity', quantityRaw,
    'unitPrice', unitPrice,
    'currency', 'CNY',
    'status', 'RESERVED',
    'requestId', ARGV[7],
    'traceparent', ARGV[11],
    'tracestate', ARGV[12],
    'occurredAtMs', tostring(nowMs),
    'activityVersion', activityVersion,
    'idempotencyKeyHash', ARGV[10],
    'requestFingerprint', ARGV[4]
)
if type(streamWrite) == 'table' and streamWrite['err'] then
    redis.pcall('DEL', KEYS[4])
    redis.pcall('DEL', KEYS[3])
    redis.pcall('DEL', KEYS[5])
    return result('INTERNAL_STATE_INVALID')
end

local stockWrite = redis.pcall('DECRBY', KEYS[2], quantity)
if type(stockWrite) == 'table' and stockWrite['err'] then
    redis.pcall('XDEL', KEYS[6], streamWrite)
    redis.pcall('DEL', KEYS[4])
    redis.pcall('DEL', KEYS[3])
    redis.pcall('DEL', KEYS[5])
    return result('INTERNAL_STATE_INVALID')
end

return result('ACCEPTED', ARGV[5], ARGV[7], streamWrite)
