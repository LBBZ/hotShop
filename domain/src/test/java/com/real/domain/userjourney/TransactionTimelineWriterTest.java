package com.real.domain.userjourney;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionTimelineWriterTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-08T06:00:00Z");

    @Test
    void retriesOnlyIgnoreTheExpectedExistingFact() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        assertThatNoException().isThrownBy(() -> write(jdbc));
    }

    @Test
    void unrelatedUniqueConflictIsNotHidden() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DuplicateKeyException duplicate = new DuplicateKeyException("unexpected duplicate");
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(duplicate);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> write(jdbc)).isSameAs(duplicate);
    }

    @Test
    void constraintFailuresAreNeverTreatedAsIdempotentReplay() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("invalid timeline fact");
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(violation);

        assertThatThrownBy(() -> write(jdbc)).isSameAs(violation);
    }

    @Test
    void nullOccurredAtIsRejectedInsteadOfFabricatingCurrentTime() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> TransactionTimelineWriter.reservation(
                jdbc,
                7L,
                "rsv_0123456789abcdef0123456789abcdef",
                null,
                "RESERVED",
                null,
                "request-1234567890",
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                "",
                "INVENTORY_RESERVED"
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("occurredAt");
    }

    private static void write(JdbcTemplate jdbc) {
        TransactionTimelineWriter.reservation(
                jdbc,
                7L,
                "rsv_0123456789abcdef0123456789abcdef",
                null,
                "RESERVED",
                OCCURRED_AT,
                "request-1234567890",
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                "",
                "INVENTORY_RESERVED"
        );
    }
}
