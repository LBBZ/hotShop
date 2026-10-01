package com.real.security.service;

import com.real.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenBlacklistServiceTest {
    @Test
    void unavailableAuthorityCannotBecomeANegativeCacheResult() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString()))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        var service = new TokenBlacklistService(jdbc, new SecurityProperties());
        assertThatThrownBy(() -> service.isBlacklisted("jti"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void expiredOutsideSkewRequiresNoWriteAndBlankIdsAreRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new TokenBlacklistService(jdbc, new SecurityProperties());
        service.revoke("expired", Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> service.isBlacklisted(" "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
