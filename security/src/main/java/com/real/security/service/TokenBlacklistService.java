package com.real.security.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class TokenBlacklistService {
    private static final String KEY_PREFIX = "hotshop:auth:deny:jti:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (!ttl.isPositive()) {
            return;
        }
        redisTemplate.opsForValue().set(key(jti), "1", ttl);
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
    }

    private String key(String jti) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JWT ID is required");
        }
        return KEY_PREFIX + sha256(jti);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
