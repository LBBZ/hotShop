package com.real.task.timeoutOrderTask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderTimeoutService {
    public static final String CONSUMER = "hotshop-legacy-order-timeout-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OrderTimeoutService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ProcessResult process(TimeoutEvent event) {
        OrderFact order = jdbc.query("""
            SELECT order_id,user_id,reservation_id,total_amount,currency,status,expires_at,
                   expires_at<=UTC_TIMESTAMP(6) AS expired
              FROM sales_order WHERE order_id=? FOR UPDATE
            """, rs -> rs.next() ? new OrderFact(
                    rs.getString("order_id"), rs.getLong("user_id"),
                    rs.getObject("reservation_id") == null ? null : rs.getLong("reservation_id"),
                    rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getString("status"),
                    rs.getTimestamp("expires_at"), rs.getBoolean("expired")) : null, event.orderId());
        if (order == null) throw new TimeoutFactConflictException("ORDER_NOT_FOUND");
        verifyFacts(event, order);

        try {
            jdbc.update("INSERT INTO processed_event(consumer_name,event_id,event_type) VALUES(?,?,?)",
                    CONSUMER, event.eventId(), event.eventType());
        } catch (DuplicateKeyException duplicate) {
            return ProcessResult.DUPLICATE;
        }

        if (order.reservationId() != null) return ProcessResult.SECKILL_NOOP;
        if (!"PENDING".equals(order.status())) return ProcessResult.TERMINAL_NOOP;
        if (!order.expired()) {
            insertReschedule(event);
            return ProcessResult.RESCHEDULED;
        }

        int itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_order_item WHERE order_id=?", Integer.class, event.orderId());
        if (itemCount <= 0) throw new TimeoutFactConflictException("ORDER_ITEMS_MISSING");
        int canceled = jdbc.update("""
            UPDATE sales_order SET status='CANCELED',version=version+1
             WHERE order_id=? AND status='PENDING' AND reservation_id IS NULL
               AND expires_at<=UTC_TIMESTAMP(6)
            """, event.orderId());
        if (canceled != 1) throw new IllegalStateException("Conditional timeout cancellation lost");
        int restored = jdbc.update("""
            UPDATE catalog_product p JOIN sales_order_item i ON i.product_id=p.product_id
               SET p.stock=p.stock+i.quantity,p.version=p.version+1
             WHERE i.order_id=?
            """, event.orderId());
        if (restored != itemCount) {
            throw new IllegalStateException("Inventory restoration row count mismatch");
        }
        insertCanceled(event);
        return ProcessResult.CANCELED;
    }

    private void verifyFacts(TimeoutEvent event, OrderFact order) {
        long databaseExpiry = order.expiresAt() == null ? 0 : order.expiresAt().toInstant().toEpochMilli();
        if (event.userId() != order.userId()
                || event.amount().compareTo(order.amount()) != 0
                || !"CNY".equals(event.currency())
                || !event.currency().equals(order.currency())
                || event.expiresAtMs() != databaseExpiry) {
            throw new TimeoutFactConflictException("ORDER_FACT_CONFLICT");
        }
    }

    private void insertReschedule(TimeoutEvent event) {
        int nextAttempt = Math.addExact(event.timeoutAttempt(), 1);
        String eventId = deterministic("LEGACY_ORDER_TIMEOUT_RESCHEDULE", event.orderId()
                + "/" + event.expiresAtMs() + "/" + nextAttempt);
        Map<String, Object> payload = basePayload(event);
        payload.put("timeoutAttempt", nextAttempt);
        insertOutbox(eventId, "LEGACY_ORDER_TIMEOUT_REQUESTED", event.orderId(), payload, true);
    }

    private void insertCanceled(TimeoutEvent event) {
        String eventId = deterministic("ORDER_CANCELED", event.orderId());
        Map<String, Object> payload = basePayload(event);
        payload.put("reason", "PAYMENT_TIMEOUT");
        insertOutbox(eventId, "ORDER_CANCELED", event.orderId(), payload, false);
    }

    private Map<String, Object> basePayload(TimeoutEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("orderId", event.orderId());
        payload.put("userId", event.userId());
        payload.put("amount", event.amount().toPlainString());
        payload.put("currency", event.currency());
        payload.put("expiresAtMs", event.expiresAtMs());
        return payload;
    }

    private void insertOutbox(String eventId, String eventType, String orderId,
            Map<String, Object> payload, boolean tolerateIdenticalReplay) {
        try {
            String body = json.writeValueAsString(payload);
            String duplicateClause = tolerateIdenticalReplay ? " ON DUPLICATE KEY UPDATE event_id=event_id" : "";
            int changed = jdbc.update("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload)
                VALUES(?, 'ORDER', ?, ?, CAST(? AS JSON))
                """ + duplicateClause, eventId, orderId, eventType, body);
            if (!tolerateIdenticalReplay && changed != 1) {
                throw new IllegalStateException("Cancellation Outbox was not inserted");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize timeout event", exception);
        }
    }

    private static String deterministic(String type, String value) {
        return UUID.nameUUIDFromBytes(("hotshop/outbox/v1/" + type + "/" + value)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record TimeoutEvent(String eventId, String eventType, String aggregateType,
            String aggregateId, String orderId, long userId, BigDecimal amount,
            String currency, long expiresAtMs, int timeoutAttempt, Instant occurredAt) { }
    private record OrderFact(String orderId, long userId, Long reservationId, BigDecimal amount,
            String currency, String status, Timestamp expiresAt, boolean expired) { }
    public enum ProcessResult { DUPLICATE, SECKILL_NOOP, TERMINAL_NOOP, RESCHEDULED, CANCELED }
}
