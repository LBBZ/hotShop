-- Activity loader v3: an unchanged offer is a read-only inventory replay,
-- even when committed orders/returns have advanced the MySQL row version.
-- KEYS: 1 metadata hash, 2 stock string, 3 activity Stream, 4 staging metadata,
--       5 staging stock, 6 reservation Stream registry Set, 7 discovery index
-- ARGV: activityId, productId, unitPrice, totalStock, availableStock, perUserLimit,
--       status, startsAtMs, endsAtMs, databaseVersion, expireAtEpochSeconds,
--       catalogStockAtLoad, bootstrapAllowed, hasDatabaseReservations

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
        or not valid_type(KEYS[5], 'string', true)
        or not valid_type(KEYS[6], 'set', true)
        or not valid_type(KEYS[7], 'zset', true) then
    return output('INTERNAL_STATE_INVALID')
end

redis.pcall('DEL', KEYS[4], KEYS[5])

local incomingVersion = tonumber(ARGV[10])
if incomingVersion == nil or incomingVersion < 0
        or not ARGV[12] or not string.match(ARGV[12], '^%d+$') then
    return output('INTERNAL_STATE_INVALID')
end

local eventCount = redis.call('XLEN', KEYS[3])
local metadataExists = redis.call('EXISTS', KEYS[1]) == 1
if eventCount > 0 and not metadataExists then
    return output('RESERVATIONS_EXIST', nil, redis.call('GET', KEYS[2]), eventCount)
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
    local initialStock = redis.call('HGET', KEYS[1], 'initialAvailableStock')
    if not initialStock or not string.match(initialStock, '^%d+$') then
        return output('INTERNAL_STATE_INVALID', currentVersionRaw, currentStock, eventCount)
    end
    local fields = {
            {'schemaVersion', '1'},
            {'activityId', ARGV[1]},
            {'productId', ARGV[2]},
            {'unitPrice', ARGV[3]},
            {'totalStock', ARGV[4]},
            {'perUserLimit', ARGV[6]},
            {'status', ARGV[7]},
            {'startsAtMs', ARGV[8]},
            {'endsAtMs', ARGV[9]}
    }
    local sameOffer = true
    for _, field in ipairs(fields) do
        if redis.call('HGET', KEYS[1], field[1]) ~= field[2] then
            sameOffer = false
        end
    end
    if sameOffer then
        local catalogStock = redis.call('HGET', KEYS[1], 'initialCatalogStock')
        -- Catalog inventory is independent of activity loading and may change
        -- through ordinary orders or audited adjustments at the same version.
        if not catalogStock then
            redis.call('HSET', KEYS[1], 'initialCatalogStock', ARGV[12])
        end
        redis.call('SADD', KEYS[6], KEYS[3])
        redis.call('ZADD', KEYS[7], 0, KEYS[3])
        return output('IDEMPOTENT', currentVersionRaw, currentStock, eventCount)
    end
    if incomingVersion == currentVersion then
        return output('INTERNAL_STATE_INVALID', currentVersionRaw, currentStock, eventCount)
    end
    -- A released reservation is still history. A missing/truncated Stream must
    -- not turn an already used quota into a fresh allocation either.
    local revision = redis.call('HGET', KEYS[1], 'inventoryRevision') or '0'
    if not string.match(revision, '^%d+$') then
        return output('INTERNAL_STATE_INVALID', currentVersionRaw, currentStock, eventCount)
    end
    if eventCount > 0 or revision ~= '0' or ARGV[14] == '1' then
        return output('RESERVATIONS_EXIST', currentVersionRaw, currentStock, eventCount)
    end
    if currentStock ~= initialStock then
        return output('INTERNAL_STATE_INVALID', currentVersionRaw, currentStock, eventCount)
    end
elseif redis.call('EXISTS', KEYS[2]) == 1 then
    return output('INTERNAL_STATE_INVALID', nil, redis.call('GET', KEYS[2]), eventCount)
elseif ARGV[14] == '1' then
    return output('RESERVATIONS_EXIST', nil, nil, eventCount)
end

-- Only a pristine offer can initialize/replace inventory. The shared catalog
-- stock cap applies here, not to a no-op reload after legitimate consumption.
if ARGV[13] ~= '1' then
    return output('ACTIVITY_INVALID')
end

local stagedMeta = redis.pcall(
    'HSET', KEYS[4],
    'schemaVersion', '1',
    'activityId', ARGV[1],
    'productId', ARGV[2],
    'unitPrice', ARGV[3],
    'totalStock', ARGV[4],
    'initialAvailableStock', ARGV[5],
    'initialCatalogStock', ARGV[12],
    'perUserLimit', ARGV[6],
    'status', ARGV[7],
    'startsAtMs', ARGV[8],
    'endsAtMs', ARGV[9],
    'databaseVersion', ARGV[10],
    'inventoryRevision', '0'
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
redis.call('SADD', KEYS[6], KEYS[3])
redis.call('ZADD', KEYS[7], 0, KEYS[3])
return output('LOADED', ARGV[10], ARGV[5], eventCount)
