-- Repeated MySQL -> redis-seckill activity loader v1.
-- KEYS: 1 metadata hash, 2 stock string, 3 activity Stream, 4 staging metadata, 5 staging stock
-- ARGV: activityId, productId, unitPrice, totalStock, availableStock, perUserLimit,
--       status, startsAtMs, endsAtMs, databaseVersion, expireAtEpochSeconds

local function output(code, redisVersion, stock, eventCount)
    return {code, redisVersion or '', stock or '', tostring(eventCount or 0)}
end

local function valid_type(key, expected, allowNone)
    local valueType = redis.call('TYPE', key)['ok']
    return valueType == expected or (allowNone and valueType == 'none')
end

if not valid_type(KEYS[1], 'hash', true)
        or not valid_type(KEYS[2], 'string', true)
        or not valid_type(KEYS[3], 'stream', true)
        or not valid_type(KEYS[4], 'hash', true)
        or not valid_type(KEYS[5], 'string', true) then
    return output('INTERNAL_STATE_INVALID')
end

redis.pcall('DEL', KEYS[4], KEYS[5])

local incomingVersion = tonumber(ARGV[10])
if incomingVersion == nil or incomingVersion < 0 then
    return output('INTERNAL_STATE_INVALID')
end

local eventCount = redis.call('XLEN', KEYS[3])
local metadataExists = redis.call('EXISTS', KEYS[1]) == 1
if eventCount > 0 and not metadataExists then
    local existingStock = redis.call('GET', KEYS[2])
    return output('RESERVATIONS_EXIST', nil, existingStock, eventCount)
end
if metadataExists then
    local currentVersionRaw = redis.call('HGET', KEYS[1], 'databaseVersion')
    if not currentVersionRaw or not string.match(currentVersionRaw, '^%d+$') then
        return output('INTERNAL_STATE_INVALID')
    end
    local currentVersion = tonumber(currentVersionRaw)
    local currentStock = redis.call('GET', KEYS[2])
    if not currentStock or not string.match(currentStock, '^%d+$') then
        return output('INTERNAL_STATE_INVALID')
    end
    if incomingVersion < currentVersion then
        return output('STALE_VERSION', currentVersionRaw, currentStock, eventCount)
    end
    if incomingVersion == currentVersion then
        local fields = {
            {'schemaVersion', '1'},
            {'activityId', ARGV[1]},
            {'productId', ARGV[2]},
            {'unitPrice', ARGV[3]},
            {'totalStock', ARGV[4]},
            {'initialAvailableStock', ARGV[5]},
            {'perUserLimit', ARGV[6]},
            {'status', ARGV[7]},
            {'startsAtMs', ARGV[8]},
            {'endsAtMs', ARGV[9]}
        }
        for _, field in ipairs(fields) do
            if redis.call('HGET', KEYS[1], field[1]) ~= field[2] then
                return output('INTERNAL_STATE_INVALID', currentVersionRaw, currentStock, eventCount)
            end
        end
        return output('IDEMPOTENT', currentVersionRaw, currentStock, eventCount)
    end
    if eventCount > 0 then
        return output('RESERVATIONS_EXIST', currentVersionRaw, currentStock, eventCount)
    end
end

local stagedMeta = redis.pcall(
    'HSET', KEYS[4],
    'schemaVersion', '1',
    'activityId', ARGV[1],
    'productId', ARGV[2],
    'unitPrice', ARGV[3],
    'totalStock', ARGV[4],
    'initialAvailableStock', ARGV[5],
    'perUserLimit', ARGV[6],
    'status', ARGV[7],
    'startsAtMs', ARGV[8],
    'endsAtMs', ARGV[9],
    'databaseVersion', ARGV[10]
)
if type(stagedMeta) == 'table' and stagedMeta['err'] then
    redis.pcall('DEL', KEYS[4], KEYS[5])
    return output('INTERNAL_STATE_INVALID')
end
local stagedStock = redis.pcall('SET', KEYS[5], ARGV[5])
if type(stagedStock) == 'table' and stagedStock['err'] then
    redis.pcall('DEL', KEYS[4], KEYS[5])
    return output('INTERNAL_STATE_INVALID')
end

local renameMeta = redis.pcall('RENAME', KEYS[4], KEYS[1])
if type(renameMeta) == 'table' and renameMeta['err'] then
    redis.pcall('DEL', KEYS[4], KEYS[5])
    return output('INTERNAL_STATE_INVALID')
end
local renameStock = redis.pcall('RENAME', KEYS[5], KEYS[2])
if type(renameStock) == 'table' and renameStock['err'] then
    redis.pcall('DEL', KEYS[5])
    return output('INTERNAL_STATE_INVALID')
end
redis.call('EXPIREAT', KEYS[1], ARGV[11])
redis.call('EXPIREAT', KEYS[2], ARGV[11])
return output('LOADED', ARGV[10], ARGV[5], eventCount)
