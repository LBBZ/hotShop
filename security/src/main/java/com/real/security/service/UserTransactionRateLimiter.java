package com.real.security.service;

import com.real.common.api.ApiException;
import com.real.security.config.SecurityProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserTransactionRateLimiter {
    private static final DefaultRedisScript<List> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('TTL', KEYS[1])}
            """,
            List.class
    );
    private static final String PREFIX = "hotshop:user-transaction:rate:";

    private final StringRedisTemplate redisTemplate;
    private final SecurityProperties properties;

    public UserTransactionRateLimiter(
            StringRedisTemplate redisTemplate,
            SecurityProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void beforeOrder(long userId) {
        enforce("order", userId, properties.getRateLimit().getUserOrderTransaction());
    }

    public void beforeReservation(long userId) {
        enforce("reservation", userId,
                properties.getRateLimit().getUserReservationTransaction());
    }

    @SuppressWarnings("unchecked")
    private void enforce(String operation, long userId, SecurityProperties.Policy policy) {
        try {
            List<Long> result = (List<Long>) redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(PREFIX + operation + ":" + AuthenticationRateLimiter.hash(
                            Long.toString(userId)
                    )),
                    Long.toString(policy.getWindowSeconds())
            );
            if (result == null || result.size() != 2) {
                throw ApiException.serviceUnavailable(
                        "TRANSACTION_RATE_LIMIT_UNAVAILABLE",
                        "Transaction admission is temporarily unavailable"
                );
            }
            long count = result.get(0);
            long retryAfter = Math.max(1, result.get(1));
            if (count > policy.getLimit()) {
                throw ApiException.rateLimited(retryAfter);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (DataAccessException | ClassCastException exception) {
            throw ApiException.serviceUnavailable(
                    "TRANSACTION_RATE_LIMIT_UNAVAILABLE",
                    "Transaction admission is temporarily unavailable"
            );
        }
    }
}
