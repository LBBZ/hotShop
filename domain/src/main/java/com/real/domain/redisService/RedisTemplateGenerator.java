package com.real.domain.redisService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class RedisTemplateGenerator {
    @Value("${spring.redis.host}")
    private String redisHost;
    @Value("${spring.redis.port}")
    private int redisPort;
    @Value("${spring.redis.password}")
    private String redisPassword;

    private final RedisTemplate<?, ?>[] redisTemplate = new RedisTemplate[16];

    private final RedisConnectionFactory redisConnectionFactory = new JedisConnectionFactory();

    public RedisTemplate<?, ?> getRedisTemplate(int dataBase) {
        if (dataBase < 0 || dataBase >= 16) {
            return null;
        }
        if (redisTemplate[dataBase] == null) {
            redisTemplate[dataBase] = new RedisTemplate<>();

        }
        return redisTemplate[dataBase];
    }


}
