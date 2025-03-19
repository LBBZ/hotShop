package com.real.domain.infra;

import com.real.infrastructure.Redis.RedisTemplateGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedisService {
    private final RedisTemplateGenerator redisTemplateGenerator;
    @Autowired
    public RedisService(RedisTemplateGenerator redisTemplateGenerator) {
        this.redisTemplateGenerator = redisTemplateGenerator;
    }

    /**
     * 设置键值对到指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     * @param value 值
     * @param ttl （存活时间），单位为秒
     */
    public void set(String key, String value, int dbIndex, long ttl) {
        // 设置键值对
        redisTemplateGenerator
                .getRedisTemplate(dbIndex)
                .opsForValue()
                .set(
                key, value,
                ttl, TimeUnit.SECONDS);
    }

    /**
     * 获取指定键的值从指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     * @return 是否存在
     */
    public Boolean hasKey(String key, int dbIndex) {
        return Boolean.TRUE.equals(
                redisTemplateGenerator
                        .getRedisTemplate(dbIndex)
                        .hasKey(key)
        );
    }

    /**
     * 获取指定键的值从指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     * @return 值
     */
    public Object get(String key, int dbIndex) {
        return redisTemplateGenerator
                .getRedisTemplate(dbIndex)
                .opsForValue()
                .get(key);
    }

    /**
     * 删除指定键从指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     */
    public void delete(String key, int dbIndex) {
        redisTemplateGenerator
                .getRedisTemplate(dbIndex)
                .delete(key);
    }
}
