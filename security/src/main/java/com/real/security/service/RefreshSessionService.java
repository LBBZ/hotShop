package com.real.security.service;

import com.real.security.config.SecurityProperties;
import com.real.security.identity.RefreshRotationResult;
import com.real.security.identity.RefreshSessionTokens;
import com.real.security.identity.SessionType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class RefreshSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final SecurityProperties properties;
    private final SecurityAuditService auditService;

    public RefreshSessionService(
            JdbcTemplate jdbcTemplate,
            SecurityProperties properties,
            SecurityAuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public RefreshSessionTokens create(
            SessionType sessionType,
            long userId,
            HttpServletRequest request
    ) {
        RefreshSessionTokens tokens = newTokens(UUID.randomUUID().toString());
        insertToken(sessionType, userId, null, tokens);
        auditService.loginSucceeded(
                sessionType == SessionType.USER ? "USER" : "ADMIN",
                userId,
                sessionType.name(),
                request
        );
        return tokens;
    }

    @Transactional
    public RefreshRotationResult rotate(
            SessionType expectedType,
            String rawRefreshToken,
            String rawCsrfToken,
            HttpServletRequest request
    ) {
        if (rawRefreshToken == null || rawCsrfToken == null) {
            return RefreshRotationResult.invalid();
        }
        List<RefreshRow> rows = jdbcTemplate.query(
                """
                SELECT
                    r.refresh_token_id, r.family_id, r.user_id, r.session_type,
                    r.csrf_hash, r.issuer, r.audience, r.status, r.expires_at,
                    u.username, u.role, u.status AS user_status, u.deleted_at
                FROM refresh_token r
                LEFT JOIN app_user u ON u.user_id = r.user_id
                WHERE r.token_hash = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> mapRow(resultSet),
                sha256(rawRefreshToken)
        );
        if (rows.size() != 1) {
            return RefreshRotationResult.invalid();
        }
        RefreshRow current = rows.getFirst();
        if (current.sessionType() != expectedType
                || !matchesDomain(current, expectedType)
                || !constantTimeEquals(current.csrfHash(), sha256(rawCsrfToken))) {
            return RefreshRotationResult.invalid();
        }
        if ("ROTATED".equals(current.status())) {
            jdbcTemplate.update(
                    """
                    UPDATE refresh_token
                    SET status = 'REUSED', last_used_at = CURRENT_TIMESTAMP(6),
                        revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP(6))
                    WHERE refresh_token_id = ?
                    """,
                    current.id()
            );
            revokeFamily(current.familyId());
            auditService.refreshReuse(
                    expectedType == SessionType.USER ? "USER" : "ADMIN",
                    current.userId(),
                    current.familyId(),
                    request
            );
            return RefreshRotationResult.reused();
        }
        if (!"ACTIVE".equals(current.status()) || !current.expiresAt().isAfter(Instant.now())) {
            if ("ACTIVE".equals(current.status())) {
                jdbcTemplate.update(
                        """
                        UPDATE refresh_token
                        SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6)
                        WHERE refresh_token_id = ?
                        """,
                        current.id()
                );
            }
            return RefreshRotationResult.invalid();
        }
        if (!isUserEligible(current, expectedType)) {
            revokeFamily(current.familyId());
            return RefreshRotationResult.invalid();
        }

        RefreshSessionTokens successor = newTokens(current.familyId());
        jdbcTemplate.update(
                """
                UPDATE refresh_token
                SET status = 'ROTATED', last_used_at = CURRENT_TIMESTAMP(6)
                WHERE refresh_token_id = ? AND status = 'ACTIVE'
                """,
                current.id()
        );
        insertToken(expectedType, current.userId(), current.id(), successor);
        return RefreshRotationResult.rotated(current.userId(), current.username(), successor);
    }

    @Transactional
    public void logout(
            SessionType expectedType,
            String rawRefreshToken
    ) {
        if (rawRefreshToken == null) {
            return;
        }
        List<RefreshRow> rows = jdbcTemplate.query(
                """
                SELECT
                    r.refresh_token_id, r.family_id, r.user_id, r.session_type,
                    r.csrf_hash, r.issuer, r.audience, r.status, r.expires_at,
                    u.username, u.role, u.status AS user_status, u.deleted_at
                FROM refresh_token r
                LEFT JOIN app_user u ON u.user_id = r.user_id
                WHERE r.token_hash = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> mapRow(resultSet),
                sha256(rawRefreshToken)
        );
        if (rows.size() == 1
                && rows.getFirst().sessionType() == expectedType
                && matchesDomain(rows.getFirst(), expectedType)) {
            revokeFamily(rows.getFirst().familyId());
        }
    }

    private void insertToken(
            SessionType sessionType,
            long userId,
            Long parentId,
            RefreshSessionTokens tokens
    ) {
        SecurityProperties.JwtDomain domain = sessionType == SessionType.USER
                ? properties.getUser()
                : properties.getAdministrator();
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token (
                    refresh_token_id, token_hash, csrf_hash, family_id, user_id,
                    session_type, parent_token_id, issuer, audience, status, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                randomPositiveLong(),
                sha256(tokens.refreshToken()),
                sha256(tokens.csrfToken()),
                tokens.familyId(),
                userId,
                sessionType.name(),
                parentId,
                domain.getIssuer(),
                domain.getAudience(),
                Timestamp.from(tokens.expiresAt())
        );
    }

    private void revokeFamily(String familyId) {
        jdbcTemplate.update(
                """
                UPDATE refresh_token
                SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP(6)
                WHERE family_id = ? AND status = 'ACTIVE'
                """,
                familyId
        );
    }

    private boolean matchesDomain(RefreshRow row, SessionType type) {
        SecurityProperties.JwtDomain domain = type == SessionType.USER
                ? properties.getUser()
                : properties.getAdministrator();
        return domain.getIssuer().equals(row.issuer())
                && domain.getAudience().equals(row.audience());
    }

    private boolean isUserEligible(RefreshRow row, SessionType type) {
        String expectedRole = type == SessionType.USER ? "ROLE_USER" : "ROLE_ADMIN";
        return row.username() != null
                && expectedRole.equals(row.role())
                && "ACTIVE".equals(row.userStatus())
                && !row.deleted();
    }

    private RefreshSessionTokens newTokens(String familyId) {
        return new RefreshSessionTokens(
                randomToken(),
                randomToken(),
                familyId,
                Instant.now().plusSeconds(properties.getRefresh().getTtlSeconds())
        );
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long randomPositiveLong() {
        long value;
        do {
            value = RANDOM.nextLong() & Long.MAX_VALUE;
        } while (value == 0);
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private RefreshRow mapRow(ResultSet resultSet) throws SQLException {
        return new RefreshRow(
                resultSet.getLong("refresh_token_id"),
                resultSet.getString("family_id"),
                resultSet.getLong("user_id"),
                SessionType.valueOf(resultSet.getString("session_type")),
                resultSet.getString("csrf_hash"),
                resultSet.getString("issuer"),
                resultSet.getString("audience"),
                resultSet.getString("status"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getString("username"),
                resultSet.getString("role"),
                resultSet.getString("user_status"),
                resultSet.getTimestamp("deleted_at") != null
        );
    }

    private record RefreshRow(
            long id,
            String familyId,
            long userId,
            SessionType sessionType,
            String csrfHash,
            String issuer,
            String audience,
            String status,
            Instant expiresAt,
            String username,
            String role,
            String userStatus,
            boolean deleted
    ) {
    }
}
