package com.real.domain.infra;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Fixed redis-cache access for legacy non-seckill callers.
 *
 * <p>The deprecated database-index arguments are intentionally ignored while callers are migrated
 * by their owning tasks. They can no longer select a logical database or create a connection.
 * Both Redis instances expose only DB 0.</p>
 */
@Service
public class RedisService {
    private final StringRedisTemplate cacheRedis;

    public RedisService(
            @Qualifier("cacheStringRedisTemplate") StringRedisTemplate cacheRedis
    ) {
        this.cacheRedis = cacheRedis;
    }

    public Boolean setWithTTL(String key, String value, long ttlSeconds) {
        return cacheRedis.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(ttlSeconds));
    }

    public Boolean setNoTTL(String key, String value) {
        return cacheRedis.opsForValue().setIfAbsent(key, value);
    }

    public Boolean hasKey(String key) {
        return Boolean.TRUE.equals(cacheRedis.hasKey(key));
    }

    public String get(String key) {
        return cacheRedis.opsForValue().get(key);
    }

    public void delete(String key) {
        cacheRedis.delete(key);
    }

    @Deprecated(forRemoval = true)
    public Boolean setWithTTL(Object key, Object value, int ignoredDbIndex, long ttlSeconds) {
        return setWithTTL(String.valueOf(key), String.valueOf(value), ttlSeconds);
    }

    @Deprecated(forRemoval = true)
    public Boolean setNoTTL(Object key, Object value, int ignoredDbIndex) {
        return setNoTTL(String.valueOf(key), String.valueOf(value));
    }

    @Deprecated(forRemoval = true)
    public Boolean hasKey(String key, int ignoredDbIndex) {
        return hasKey(key);
    }

    @Deprecated(forRemoval = true)
    public String get(String key, int ignoredDbIndex) {
        return get(key);
    }

    @Deprecated(forRemoval = true)
    public void delete(String key, int ignoredDbIndex) {
        delete(key);
    }
}
