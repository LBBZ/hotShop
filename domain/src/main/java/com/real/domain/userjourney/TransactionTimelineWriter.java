package com.real.domain.userjourney;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Writes the durable user-facing projection in the caller's database transaction. */
public final class TransactionTimelineWriter {
    private TransactionTimelineWriter() {
    }

    public static void reservation(
            JdbcTemplate jdbc,
            long userId,
            String reservationNo,
            String orderId,
            String eventType,
            Instant occurredAt,
            String requestId,
            String traceparent,
            String tracestate,
            String detailCode
    ) {
        insert(jdbc, userId, "RESERVATION", reservationNo, reservationNo, orderId,
                eventType, occurredAt, requestId, traceparent, tracestate, detailCode);
    }

    public static void order(
            JdbcTemplate jdbc,
            long userId,
            String orderId,
            String eventType,
            Instant occurredAt,
            String requestId,
            String traceparent,
            String tracestate,
            String detailCode
    ) {
        insert(jdbc, userId, "ORDER", orderId, null, orderId, eventType, occurredAt,
                requestId, traceparent, tracestate, detailCode);
        List<String> reservations = jdbc.queryForList(
                "SELECT reservation_no FROM sale_reservation WHERE order_id = ? AND user_id = ?",
                String.class,
                orderId,
                userId
        );
        if (reservations.size() == 1) {
            insert(jdbc, userId, "RESERVATION", reservations.get(0), reservations.get(0), orderId,
                    eventType, occurredAt, requestId, traceparent, tracestate, detailCode);
        }
    }

    private static void insert(
            JdbcTemplate jdbc,
            long userId,
            String resourceType,
            String resourceId,
            String reservationNo,
            String orderId,
            String eventType,
            Instant occurredAt,
            String requestId,
            String traceparent,
            String tracestate,
            String detailCode
    ) {
        try {
            jdbc.update("""
                INSERT INTO user_transaction_timeline (
                    user_id, resource_type, resource_id, reservation_no, order_id,
                    event_type, request_id, traceparent, tracestate, detail_code, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), ?, ?)
                """, userId, resourceType, resourceId, reservationNo, orderId, eventType,
                    requestId, traceparent, tracestate, detailCode,
                    Timestamp.from(Objects.requireNonNull(occurredAt, "occurredAt")));
        } catch (DuplicateKeyException exception) {
            Integer expectedFact = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM user_transaction_timeline
                     WHERE resource_type = ?
                       AND resource_id = ?
                       AND event_type = ?
                       AND user_id = ?
                       AND reservation_no <=> ?
                       AND order_id <=> ?
                    """, Integer.class, resourceType, resourceId, eventType, userId,
                    reservationNo, orderId);
            if (expectedFact == null || expectedFact != 1) {
                throw exception;
            }
        }
    }
}
