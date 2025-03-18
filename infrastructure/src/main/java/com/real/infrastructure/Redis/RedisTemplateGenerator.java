package com.real.infrastructure.Redis;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class RedisTemplateGenerator {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    // 使用ConcurrentHashMap存储RedisTemplate实例，确保线程安全
    private final ConcurrentMap<Integer, RedisTemplate<Object, Object>> redisTemplateCache = new ConcurrentHashMap<>();

    /**
     * 用于根据配置建立工厂
     * @param database 数据库编号
     * @return LettuceConnectionFactory
     */
    private LettuceConnectionFactory createLettuceConnectionFactory(int database) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(redisHost);
        configuration.setPort(redisPort);
        if (!redisPassword.isEmpty()) {
            configuration.setPassword(redisPassword);
        }
        configuration.setDatabase(database);
        LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory(configuration);
        lettuceConnectionFactory.afterPropertiesSet();
        return lettuceConnectionFactory;
    }

    /**
     * 创建RedisTemplate实例
     * @param database 数据库编号
     * @return RedisTemplate
     */
    private RedisTemplate<Object, Object> createRedisTemplate(int database) {
        LettuceConnectionFactory lettuceConnectionFactory = createLettuceConnectionFactory(database);
        RedisTemplate<Object, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(lettuceConnectionFactory);
        // 配置序列化
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new JdkSerializationRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new JdkSerializationRedisSerializer());
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    /**
     * 获取RedisTemplate实例
     * @param database 数据库编号
     * @return RedisTemplate
     */
    public RedisTemplate<Object, Object> getRedisTemplate(int database) {
        if (database < 0 || database >= 16) {
            throw new IllegalArgumentException("Database index out of bounds. Must be between 0 and 15.");
        }
        return redisTemplateCache.computeIfAbsent(database, this::createRedisTemplate);
    }
}