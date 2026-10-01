package com.real.security.service;

import com.real.common.api.ApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import com.real.security.config.SecurityProperties;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class ClientAssertionReplayService {
    private final JdbcTemplate jdbcTemplate;
    private final long retentionSkewSeconds;

    public ClientAssertionReplayService(JdbcTemplate jdbcTemplate, SecurityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionSkewSeconds = Math.max(0, properties.getClockSkewSeconds()) + 1;
    }

    // The unique key is the cross-process arbiter; a cache miss cannot allow replay.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeOnce(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || !expiresAt.isAfter(Instant.now())) {
            throw new BadCredentialsException("Invalid client assertion");
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO security_token_marker (marker_type, token_hash, expires_at)
                    VALUES ('CLIENT_ASSERTION', ?, ?)
                    """, TokenBlacklistService.tokenHash(jti), Timestamp.from(expiresAt.plusSeconds(retentionSkewSeconds)));
        } catch (DuplicateKeyException exception) {
            throw new BadCredentialsException("Invalid client assertion");
        } catch (DataAccessException exception) {
            throw ApiException.serviceUnavailable(
                    "AUTHENTICATION_SERVICE_UNAVAILABLE",
                    "Authentication services are temporarily unavailable"
            );
        }
    }
}
