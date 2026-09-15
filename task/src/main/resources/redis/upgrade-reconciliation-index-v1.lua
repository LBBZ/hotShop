-- Explicit one-time compatibility upgrade, NOT called by runBatch.
-- KEYS: legacy registry SET, new reconciliation ZSET, durable upgrade HASH.
-- ARGV: advisory SSCAN COUNT (one scan step per invocation).
-- SSCAN COUNT is not a hard record bound; inspect returned work and persist
-- the cursor atomically with every ZADD. New loaders dual-write both indexes.
local cursor = redis.call('HGET', KEYS[3], 'cursor') or '0'
local page = redis.call('SSCAN', KEYS[1], cursor, 'COUNT', ARGV[1])
for _, stream in ipairs(page[2]) do redis.call('ZADD', KEYS[2], 0, stream) end
redis.call('HSET', KEYS[3], 'cursor', page[1],
    'state', page[1] == '0' and 'COMPLETE' or 'IN_PROGRESS')
redis.call('HINCRBY', KEYS[3], 'visited', #page[2])
return {page[1], tostring(#page[2]), tostring(redis.call('ZCARD', KEYS[2]))}
