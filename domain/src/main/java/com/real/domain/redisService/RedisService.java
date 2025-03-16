package com.real.domain.redisService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedisService {
    private final RedisTemplate<String, String> redisTemplate;
    @Autowired
    public RedisService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 设置 Redis 数据库
     * @param dbIndex 数据库索引
     */
    public void setDatabase(int dbIndex) {
        redisTemplate.getConnectionFactory().getConnection().select(dbIndex);
    }

    /**
     * 设置键值对到指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     * @param value 值
     * @param ttl （存活时间），单位为秒
     */
    public void set(String key, String value, int dbIndex, long ttl) {
        setDatabase(dbIndex);
        // 设置键值对
        redisTemplate.opsForValue().set(key, value,
                ttl, TimeUnit.SECONDS);
    }

    /**
     * 获取指定键的值从指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     * @return 是否存在
     */
    public Boolean hasKey(String key, int dbIndex) {
        setDatabase(dbIndex);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
    /**
     * 获取指定键的值从指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     * @return 值
     */
    public String get(String key, int dbIndex) {
        setDatabase(dbIndex);
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 删除指定键从指定的 Redis 数据库
     * @param dbIndex 数据库索引
     * @param key 键
     */
    public void delete(String key, int dbIndex) {
        setDatabase(dbIndex);
        redisTemplate.delete(key);
    }
}
