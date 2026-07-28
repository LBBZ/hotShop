package com.real.security.service;

import com.real.common.api.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class ClientAssertionReplayService {
    private static final String KEY_PREFIX = "hotshop:auth:assertion:jti:";

    private final StringRedisTemplate redisTemplate;

    public ClientAssertionReplayService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void consumeOnce(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (!ttl.isPositive()) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "Invalid client assertion"
            );
        }
        try {
            Boolean stored = redisTemplate.opsForValue().setIfAbsent(
                    KEY_PREFIX + TokenBlacklistService.sha256(jti),
                    "1",
                    ttl
            );
            if (!Boolean.TRUE.equals(stored)) {
                throw new org.springframework.security.authentication.BadCredentialsException(
                        "Invalid client assertion"
                );
            }
        } catch (DataAccessException exception) {
            throw ApiException.serviceUnavailable(
                    "AUTHENTICATION_SERVICE_UNAVAILABLE",
                    "Authentication services are temporarily unavailable"
            );
        }
    }
}
