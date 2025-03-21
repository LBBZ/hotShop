package com.real.security.service;

import com.real.domain.infra.RedisService;
import com.real.security.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class TokenBlacklistService {
    private final RedisService redisService;
    private final JwtTokenUtil jwtTokenUtil;
    private static final String BLACKLIST_KEY = "jwt:blacklist";
    @Autowired
    public TokenBlacklistService(JwtTokenUtil jwtTokenUtil, RedisService redisService) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.redisService = redisService;
    }

    public void addToBlacklist(String token) {
        // 令牌剩余有效期（秒）
        long ttl = (jwtTokenUtil.getExpirationDateFromToken(token).getTime() - System.currentTimeMillis())/1000 + 1;
        String hashToken = hashToken(token);
        if (ttl > 0) {
            redisService.setWithTTL(
                    BLACKLIST_KEY + ":" + hashToken,
                    "invalid",
                    0,
                    ttl
            );
        }
    }

    public boolean isBlacklisted(String token) {
        String hashToken = hashToken(token);
        return Boolean.TRUE.equals(redisService.hasKey(BLACKLIST_KEY + ":" + hashToken, 0));
    }

    private String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
