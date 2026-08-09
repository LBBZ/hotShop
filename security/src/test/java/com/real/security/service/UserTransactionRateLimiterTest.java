package com.real.security.service;

import com.real.common.api.ApiException;
import com.real.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserTransactionRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SecurityProperties properties = new SecurityProperties();
    private final UserTransactionRateLimiter limiter =
            new UserTransactionRateLimiter(redis, properties);

    @Test
    void admitsRequestsWithinTheConfiguredWindow() {
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenReturn(List.of(8L, 31L));

        assertThatNoException().isThrownBy(() -> limiter.beforeOrder(41L));
    }

    @Test
    void rejectsTheNinthOrderWithRetryAfter() {
        when(redis.execute(any(), anyList(), any(Object[].class)))
                .thenReturn(List.of(9L, 29L));

        assertThatThrownBy(() -> limiter.beforeOrder(41L))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(429);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(29L);
                });
    }
}
