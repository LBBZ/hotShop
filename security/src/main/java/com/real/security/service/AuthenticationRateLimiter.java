package com.real.security.service;

import com.real.common.api.ApiException;
import com.real.security.config.SecurityProperties;
import com.real.security.identity.SessionType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class AuthenticationRateLimiter {
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            return {count, ttl}
            """,
            List.class
    );
    private static final String PREFIX = "hotshop:auth:rate:";

    private final StringRedisTemplate redisTemplate;
    private final ClientAddressResolver addressResolver;
    private final SecurityProperties properties;

    public AuthenticationRateLimiter(
            StringRedisTemplate redisTemplate,
            ClientAddressResolver addressResolver,
            SecurityProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.addressResolver = addressResolver;
        this.properties = properties;
    }

    public void beforeLogin(SessionType sessionType, HttpServletRequest request, String username) {
        SecurityProperties.RateLimit limits = properties.getRateLimit();
        String ipHash = hash(addressResolver.resolve(request));
        String usernameHash = hash(normalizeUsername(username));
        if (sessionType == SessionType.USER) {
            enforce("user-login:ip:" + ipHash, limits.getUserLoginIp());
            enforce(
                    "user-login:identity:" + hash(ipHash + ":" + usernameHash),
                    limits.getUserLoginIdentity()
            );
        } else {
            enforce("administrator-login:ip:" + ipHash, limits.getAdministratorLoginIp());
            enforce(
                    "administrator-login:identity:" + hash(ipHash + ":" + usernameHash),
                    limits.getAdministratorLoginIdentity()
            );
        }
    }

    public void recordLoginFailure(SessionType sessionType, HttpServletRequest request, String username) {
        SecurityProperties.RateLimit limits = properties.getRateLimit();
        String subject = hash(
                hash(addressResolver.resolve(request)) + ":" + hash(normalizeUsername(username))
        );
        enforce(
                (sessionType == SessionType.USER
                        ? "user-login:failure:"
                        : "administrator-login:failure:") + subject,
                sessionType == SessionType.USER
                        ? limits.getUserLoginFailure()
                        : limits.getAdministratorLoginFailure()
        );
    }

    public void beforeRefresh(SessionType sessionType, HttpServletRequest request) {
        SecurityProperties.RateLimit limits = properties.getRateLimit();
        enforce(
                (sessionType == SessionType.USER ? "user-refresh:ip:" : "administrator-refresh:ip:")
                        + hash(addressResolver.resolve(request)),
                sessionType == SessionType.USER
                        ? limits.getUserRefresh()
                        : limits.getAdministratorRefresh()
        );
    }

    public void beforeAgentExchange(HttpServletRequest request) {
        enforce(
                "agent-exchange:ip:" + hash(addressResolver.resolve(request)),
                properties.getRateLimit().getAgentExchange()
        );
    }

    @SuppressWarnings("unchecked")
    private void enforce(String suffix, SecurityProperties.Policy policy) {
        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(PREFIX + suffix),
                    Long.toString(policy.getWindowSeconds())
            );
            if (result == null || result.size() != 2) {
                throw ApiException.serviceUnavailable(
                        "AUTHENTICATION_SERVICE_UNAVAILABLE",
                        "Authentication services are temporarily unavailable"
                );
            }
            long count = result.get(0);
            long ttl = Math.max(1, result.get(1));
            if (count > policy.getLimit()) {
                throw ApiException.rateLimited(ttl);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException | ClassCastException exception) {
            throw ApiException.serviceUnavailable(
                    "AUTHENTICATION_SERVICE_UNAVAILABLE",
                    "Authentication services are temporarily unavailable"
            );
        }
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
