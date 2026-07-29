package com.real.infrastructure.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HotShopRedisProperties.class)
public class RedisConnectionsConfiguration {
    public static final int DATABASE_ZERO = 0;

    @Bean(name = {"redisConnectionFactory", "cacheRedisConnectionFactory"})
    @Primary
    public LettuceConnectionFactory cacheRedisConnectionFactory(HotShopRedisProperties properties) {
        return connectionFactory(properties.getCache());
    }

    @Bean(name = "seckillRedisConnectionFactory")
    public LettuceConnectionFactory seckillRedisConnectionFactory(HotShopRedisProperties properties) {
        return connectionFactory(properties.getSeckill());
    }

    @Bean(name = {"stringRedisTemplate", "cacheStringRedisTemplate"})
    @Primary
    public StringRedisTemplate cacheStringRedisTemplate(
            @Qualifier("cacheRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(name = "seckillStringRedisTemplate")
    public StringRedisTemplate seckillStringRedisTemplate(
            @Qualifier("seckillRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        return new StringRedisTemplate(connectionFactory);
    }

    private LettuceConnectionFactory connectionFactory(HotShopRedisProperties.Instance properties) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                properties.getHost(),
                properties.getPort()
        );
        standalone.setDatabase(DATABASE_ZERO);
        if (StringUtils.hasText(properties.getPassword())) {
            standalone.setPassword(RedisPassword.of(properties.getPassword()));
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(properties.getTimeout())
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeout())
                .clientOptions(clientOptions)
                .build();
        return new LettuceConnectionFactory(standalone, client);
    }
}
