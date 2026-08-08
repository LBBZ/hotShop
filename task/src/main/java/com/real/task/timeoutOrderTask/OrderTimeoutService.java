package com.real.task.timeoutOrderTask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.audit.InventoryCompensationAuditState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
            SELECT order_id,user_id,reservation_id,total_amount,currency,status,
                   TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',expires_at) DIV 1000 AS expires_at_ms,
                   expires_at<=UTC_TIMESTAMP(6) AS expired
              FROM sales_order WHERE order_id=? FOR UPDATE
            """, rs -> rs.next() ? new OrderFact(
                    rs.getString("order_id"), rs.getLong("user_id"),
                    rs.getObject("reservation_id") == null ? null : rs.getLong("reservation_id"),
                    rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getString("status"),
                    rs.getLong("expires_at_ms"), rs.getBoolean("expired")) : null, event.orderId());
        if (order == null) throw new TimeoutFactConflictException("ORDER_NOT_FOUND");
        verifyFacts(event, order);

        try {
            jdbc.update("INSERT INTO processed_event(consumer_name,event_id,event_type) VALUES(?,?,?)",
                    CONSUMER, event.eventId(), event.eventType());
        } catch (DuplicateKeyException duplicate) {
            return ProcessResult.DUPLICATE;
        }

        if (!"PENDING".equals(order.status())) return ProcessResult.TERMINAL_NOOP;
        if (!order.expired()) {
            insertReschedule(event);
            return ProcessResult.RESCHEDULED;
        }

        PaymentFact payment = jdbc.query("""
            SELECT payment_no,status FROM payment_order
             WHERE order_id=? AND provider='MOCK' FOR UPDATE
            """, rs -> rs.next() ? new PaymentFact(rs.getString(1), rs.getString(2)) : null,
                event.orderId());

        ReservationFact reservation = null;
        if (order.reservationId() != null) {
            reservation = jdbc.query("""
                SELECT reservation_id,reservation_no,activity_id,product_id,quantity,status
                  FROM sale_reservation WHERE reservation_id=? FOR UPDATE
                """, rs -> rs.next() ? new ReservationFact(rs.getLong(1), rs.getString(2), rs.getLong(3),
                    rs.getLong(4), rs.getInt(5), rs.getString(6)) : null, order.reservationId());
            if (reservation == null || !"ORDER_CREATED".equals(reservation.status())) {
                throw new TimeoutFactConflictException("RESERVATION_FACT_CONFLICT");
            }
            lockActivity(reservation.activityId());
            lockProduct(reservation.productId());
        }

        int itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_order_item WHERE order_id=?", Integer.class, event.orderId());
        if (itemCount <= 0) throw new TimeoutFactConflictException("ORDER_ITEMS_MISSING");
        int canceled = jdbc.update("""
            UPDATE sales_order SET status='CANCELED',version=version+1
             WHERE order_id=? AND status='PENDING'
               AND expires_at<=UTC_TIMESTAMP(6)
            """, event.orderId());
        if (canceled != 1) throw new IllegalStateException("Conditional timeout cancellation lost");
        if (payment != null && "PENDING".equals(payment.status())) {
            int closed = jdbc.update("""
                UPDATE payment_order SET status='CLOSED',version=version+1
                 WHERE payment_no=? AND status='PENDING'
                """, payment.paymentNo());
            if (closed != 1) throw new IllegalStateException("Payment close lost");
        }
        int restored;
        if (reservation == null) {
            restored = jdbc.update("""
                UPDATE catalog_product p JOIN sales_order_item i ON i.product_id=p.product_id
                   SET p.stock=p.stock+i.quantity,p.version=p.version+1
                 WHERE i.order_id=?
                """, event.orderId());
            if (restored != itemCount) {
                throw new IllegalStateException("Inventory restoration row count mismatch");
            }
        } else {
            restored = jdbc.update("UPDATE catalog_product SET stock=stock+?,version=version+1 WHERE product_id=?",
                    reservation.quantity(), reservation.productId());
            int activity = jdbc.update("""
                UPDATE flash_sale_activity SET available_stock=available_stock+?,version=version+1
                 WHERE activity_id=? AND available_stock+?<=total_stock
                """, reservation.quantity(), reservation.activityId(), reservation.quantity());
            int terminal = jdbc.update("""
                UPDATE sale_reservation SET status='CANCELED',version=version+1
                 WHERE reservation_id=? AND status='ORDER_CREATED'
                """, reservation.reservationId());
            if (restored != 1 || activity != 1 || terminal != 1) {
                throw new IllegalStateException("Flash Sale timeout compensation mismatch");
            }
            insertSeckillExpired(event, reservation);
        }
        insertInventoryCompensationAudit(event, reservation, restored);
        insertCanceled(event);
        return ProcessResult.CANCELED;
    }

    private void verifyFacts(TimeoutEvent event, OrderFact order) {
        if (event.userId() != order.userId()
                || event.amount().compareTo(order.amount()) != 0
                || !"CNY".equals(event.currency())
                || !event.currency().equals(order.currency())
                || event.expiresAtMs() != order.expiresAtMs()) {
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

    private void insertSeckillExpired(TimeoutEvent event, ReservationFact reservation) {
        String eventId = deterministic("SECKILL_PAYMENT_EXPIRED", event.orderId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("orderId", event.orderId());
        payload.put("reservationNo", reservation.reservationNo());
        payload.put("userId", event.userId());
        payload.put("activityId", reservation.activityId());
        payload.put("productId", reservation.productId());
        payload.put("quantity", reservation.quantity());
        payload.put("reason", "PAYMENT_TIMEOUT");
        insertOutbox(eventId, "SECKILL_PAYMENT_EXPIRED", event.orderId(), payload, false);
    }

    private void insertInventoryCompensationAudit(TimeoutEvent event,
            ReservationFact reservation, int restoredQuantity) {
        String orderType = reservation == null ? "ORDINARY" : "SECKILL";
        String resourceType = reservation == null ? "SALES_ORDER" : "SALE_RESERVATION";
        String resourceId = reservation == null ? event.orderId() : reservation.reservationNo();
        InventoryCompensationAuditState state = new InventoryCompensationAuditState(
                orderType, "PAYMENT_TIMEOUT", event.orderId(),
                reservation == null ? null : reservation.reservationNo(), restoredQuantity);
        try {
            int inserted = jdbc.update("""
                INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,result,
                  request_id,trace_id,source,state_summary)
                VALUES('SYSTEM',NULL,'INVENTORY_COMPENSATED',?,?,'SUCCESS',NULL,NULL,'TASK',CAST(? AS JSON))
                """, resourceType, resourceId, json.writeValueAsString(state));
            if (inserted != 1) throw new IllegalStateException("Inventory compensation audit was not inserted");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize inventory compensation audit", exception);
        }
    }

    private void lockActivity(long activityId) {
        Integer found = jdbc.query("SELECT 1 FROM flash_sale_activity WHERE activity_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, activityId);
        if (found == null) throw new TimeoutFactConflictException("ACTIVITY_FACT_CONFLICT");
    }

    private void lockProduct(long productId) {
        Integer found = jdbc.query("SELECT 1 FROM catalog_product WHERE product_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null, productId);
        if (found == null) throw new TimeoutFactConflictException("PRODUCT_FACT_CONFLICT");
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
            String currency, String status, long expiresAtMs, boolean expired) { }
    private record PaymentFact(String paymentNo, String status) { }
    private record ReservationFact(long reservationId, String reservationNo, long activityId,
                                   long productId, int quantity, String status) { }
    public enum ProcessResult { DUPLICATE, TERMINAL_NOOP, RESCHEDULED, CANCELED }
}
