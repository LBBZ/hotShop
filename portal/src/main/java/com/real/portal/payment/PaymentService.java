package com.real.portal.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.ApiException;
import com.real.common.api.dto.MockPaymentActionRequest;
import com.real.common.api.dto.MockPaymentActionResponse;
import com.real.common.api.dto.PaymentResponse;
import com.real.domain.payment.MockPaymentProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {
    private static final String NOTICE = "Mock Payment only; no real funds are transferred";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MockPaymentProperties properties;

    public PaymentService(JdbcTemplate jdbc, ObjectMapper json, MockPaymentProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
    }

    @Transactional
    public PaymentResponse create(long userId, String orderId) {
        requireEnabled();
        OrderFact order = lockOrder(orderId);
        if (order == null || order.userId() != userId) throw ApiException.notFound("Order");
        if (!"PENDING".equals(order.status())) {
            throw ApiException.conflict("ORDER_NOT_PAYABLE", "Paid or canceled Orders cannot create a Payment");
        }
        PaymentFact existing = findByOrder(orderId);
        if (existing != null) return response(existing);
        if (order.expiresAt() == null) {
            throw ApiException.conflict("ORDER_PAYMENT_DEADLINE_MISSING", "Order has no payment deadline");
        }
        String paymentNo = "MOCK_" + UUID.randomUUID().toString().replace("-", "");
        int inserted = jdbc.update("""
            INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,expires_at)
            SELECT ?,order_id,'MOCK',total_amount,currency,'PENDING',expires_at
              FROM sales_order WHERE order_id=?
            """, paymentNo, order.orderId());
        if (inserted != 1) throw new IllegalStateException("Payment Order insert failed");
        PaymentFact complete = findByOrder(orderId);
        if (complete == null) throw new IllegalStateException("Payment Order cannot be reloaded");
        return response(complete);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(long userId, String paymentNo) {
        PaymentFact payment = findOwned(paymentNo, userId, false);
        if (payment == null) throw ApiException.notFound("Payment");
        return response(payment);
    }

    @Transactional
    public MockPaymentActionResponse action(long userId, String paymentNo, MockPaymentActionRequest request) {
        requireEnabled();
        PaymentFact payment = findOwned(paymentNo, userId, true);
        if (payment == null) throw ApiException.notFound("Payment");
        if (!"PENDING".equals(payment.status()) && !"FAILED".equals(payment.status())) {
            throw ApiException.conflict("PAYMENT_NOT_ACTIONABLE", "The Mock Payment is already terminal");
        }
        Duration delay = request.delay();
        if (delay.isNegative() || delay.compareTo(properties.getMaxSimulationDelay()) > 0
                || request.duplicateCount() > properties.getMaxDuplicateCount()) {
            throw ApiException.badRequest("MOCK_ACTION_LIMIT_EXCEEDED", "Mock action limits were exceeded");
        }
        String callbackId = UUID.randomUUID().toString();
        String transactionNo = "MOCK-TXN-" + UUID.randomUUID().toString().replace("-", "");
        Instant occurredAt = Instant.now();
        Instant availableAt = occurredAt.plus(delay);
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("callbackId", callbackId);
        callback.put("paymentNo", payment.paymentNo());
        callback.put("providerTransactionNo", transactionNo);
        callback.put("outcome", request.outcome());
        callback.put("amount", payment.amount().setScale(2).toPlainString());
        callback.put("currency", payment.currency());
        callback.put("occurredAt", occurredAt.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("callback", callback);
        payload.put("duplicateCount", request.duplicateCount());
        try {
            jdbc.update("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,available_at)
                VALUES(?,'PAYMENT',?,'MOCK_PAYMENT_CALLBACK_REQUESTED',CAST(? AS JSON),?)
                """, UUID.randomUUID().toString(), payment.paymentNo(),
                    json.writeValueAsString(payload), LocalDateTime.ofInstant(availableAt, ZoneOffset.UTC));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not create persistent Mock callback", exception);
        }
        return new MockPaymentActionResponse(callbackId, "MOCK", payment.paymentNo(), request.outcome(),
                availableAt, request.duplicateCount(), true, NOTICE);
    }

    private OrderFact lockOrder(String orderId) {
        return jdbc.query("""
            SELECT order_id,user_id,total_amount,currency,status,expires_at
              FROM sales_order WHERE order_id=? FOR UPDATE
            """, rs -> rs.next() ? new OrderFact(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3),
                    rs.getString(4), rs.getString(5), rs.getTimestamp(6)) : null, orderId);
    }

    private PaymentFact findByOrder(String orderId) {
        return jdbc.query("""
            SELECT payment_no,order_id,amount,currency,status,expires_at,paid_at
              FROM payment_order WHERE order_id=? AND provider='MOCK'
            """, rs -> rs.next() ? fact(rs) : null, orderId);
    }

    private PaymentFact findOwned(String paymentNo, long userId, boolean lock) {
        String sql = """
            SELECT p.payment_no,p.order_id,p.amount,p.currency,p.status,p.expires_at,p.paid_at
              FROM payment_order p JOIN sales_order o ON o.order_id=p.order_id
             WHERE p.payment_no=? AND p.provider='MOCK' AND o.user_id=?
            """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, rs -> rs.next() ? fact(rs) : null, paymentNo, userId);
    }

    private PaymentFact fact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentFact(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6), rs.getTimestamp(7));
    }

    private PaymentResponse response(PaymentFact p) {
        return new PaymentResponse(p.paymentNo(), p.orderId(), "MOCK", p.amount(), p.currency(), p.status(),
                instant(p.expiresAt()), instant(p.paidAt()), true, NOTICE);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw ApiException.serviceUnavailable("MOCK_PAYMENT_DISABLED", "Local Mock Payment is disabled");
        }
    }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record OrderFact(String orderId, long userId, BigDecimal amount, String currency,
                             String status, Timestamp expiresAt) { }
    private record PaymentFact(String paymentNo, String orderId, BigDecimal amount, String currency,
                               String status, Timestamp expiresAt, Timestamp paidAt) { }
}
