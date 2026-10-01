package com.real.security.service;

import com.real.security.config.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class TokenBlacklistService {
    private final JdbcTemplate jdbcTemplate;
    private final long retentionSkewSeconds;

    public TokenBlacklistService(JdbcTemplate jdbcTemplate, SecurityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionSkewSeconds = Math.max(0, properties.getClockSkewSeconds()) + 1;
    }

    // Revocation survives cache eviction and commits even if the caller later rolls back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(String jti, Instant expiresAt) {
        String tokenHash = tokenHash(jti);
        Instant retainUntil = expiresAt.plusSeconds(retentionSkewSeconds);
        if (!retainUntil.isAfter(Instant.now())) {
            return;
        }
        Timestamp expiry = Timestamp.from(retainUntil);
        jdbcTemplate.update("""
                INSERT INTO security_token_marker (marker_type, token_hash, expires_at)
                VALUES ('REVOKED_ACCESS', ?, ?)
                ON DUPLICATE KEY UPDATE expires_at = GREATEST(expires_at, ?)
                """, tokenHash, expiry, expiry);
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM security_token_marker
                    WHERE marker_type = 'REVOKED_ACCESS' AND token_hash = ?
                      AND expires_at > UTC_TIMESTAMP(6))
                """, Boolean.class, tokenHash(jti)));
    }

    static String tokenHash(String jti) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JWT ID is required");
        }
        return sha256(jti);
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
