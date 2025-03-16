package com.real.domain.redisService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisTemplateGenerator {
    @Value("${spring.redis.host}")
    private String redisHost;
    @Value("${spring.redis.port}")
    private int redisPort;
    @Value("${spring.redis.password}")
    private String redisPassword;
    // 存储redis数据库链接
    private final RedisTemplate<?, ?>[] redisTemplate = new RedisTemplate[16];

    /**
     * 用于根据配置建立工厂
     * @param dateBase 数据库编号
     */
    private LettuceConnectionFactory redisLettuceConnectionFactory(int dateBase) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(redisHost);
        configuration.setPort(redisPort);
        configuration.setPassword(redisPassword);
        configuration.setDatabase(dateBase);
        LettuceConnectionFactory lettuceConnectionFactory = new LettuceConnectionFactory(configuration);
        lettuceConnectionFactory.afterPropertiesSet();
        return lettuceConnectionFactory;
    }

    /**
     * 用于根据配置建立RedisTemplate
     * @param dateBase 数据库编号
     * @return 返回建立的RedisTemplate
     */
    private RedisTemplate<?, ?> generateRedisTemplate(int dateBase) {
        LettuceConnectionFactory lettuceConnectionFactory = redisLettuceConnectionFactory(dateBase);
        RedisTemplate<?, ?> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(lettuceConnectionFactory);
        // 配置序列化
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new JdkSerializationRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new JdkSerializationRedisSerializer());

        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }

    public RedisTemplate<?, ?> getRedisTemplate(int dataBase) {
        if (dataBase < 0 || dataBase >= 16) {
            return null;
        }
        if (redisTemplate[dataBase] == null) {
            redisTemplate[dataBase] = generateRedisTemplate(dataBase);
        }
        return redisTemplate[dataBase];
    }


}
