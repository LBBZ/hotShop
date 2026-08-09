package com.real.portal.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.dto.MockPaymentCallbackResponse;
import com.real.domain.payment.MockPaymentProperties;
import com.real.domain.payment.PaymentProvider;
import com.real.common.observability.AsyncTraceContext;
import com.real.domain.userjourney.TransactionTimelineWriter;
import org.slf4j.MDC;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class MockPaymentCallbackService {
    private static final Set<String> FIELDS = Set.of("callbackId", "paymentNo",
            "providerTransactionNo", "outcome", "amount", "currency", "occurredAt");
    private static final Pattern PAYMENT_NO = Pattern.compile("^MOCK_[0-9a-f]{32}$");
    private static final Pattern TRANSACTION_NO = Pattern.compile("^MOCK-TXN-[0-9a-f]{32}$");
    private static final Pattern MONEY = Pattern.compile("^(0|[1-9][0-9]{0,16})\\.[0-9]{2}$");
    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PaymentProvider provider;
    private final MockPaymentProperties properties;
    private final PaymentCallbackAuditService audit;
    private final TransactionTemplate tx;
    private final TransactionTemplate nonceTx;
    private final Clock clock = Clock.systemUTC();

    public MockPaymentCallbackService(JdbcTemplate jdbc, ObjectMapper json, PaymentProvider provider,
            MockPaymentProperties properties, PaymentCallbackAuditService audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.json = json;
        this.provider = provider;
        this.properties = properties;
        this.audit = audit;
        this.tx = tx;
        this.nonceTx = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        this.nonceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MockPaymentCallbackResponse accept(String timestamp, String nonce, String signature,
            byte[] body, HttpServletRequest request) {
        if (!properties.isEnabled()) throw reject("PROVIDER_DISABLED", HttpStatus.SERVICE_UNAVAILABLE);
        if (body == null || body.length == 0 || body.length > properties.getMaxCallbackBodyBytes()) {
            throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
        }
        long seconds;
        try {
            if (timestamp == null || !timestamp.matches("^[0-9]{10}$")) throw new NumberFormatException();
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            throw reject("TIMESTAMP_INVALID", HttpStatus.UNAUTHORIZED);
        }
        Instant signedAt;
        try { signedAt = Instant.ofEpochSecond(seconds); }
        catch (DateTimeException exception) { throw reject("TIMESTAMP_INVALID", HttpStatus.UNAUTHORIZED); }
        Duration distance = Duration.between(signedAt, clock.instant()).abs();
        if (distance.compareTo(properties.getAllowedClockSkew()) > 0) {
            throw reject("TIMESTAMP_OUTSIDE_WINDOW", HttpStatus.UNAUTHORIZED);
        }
        if (nonce == null || !NONCE.matcher(nonce).matches()
                || !provider.verify(timestamp, nonce, body, signature)) {
            throw reject("SIGNATURE_INVALID", HttpStatus.UNAUTHORIZED);
        }
        Callback callback = parse(body);
        if (Duration.between(callback.occurredAt(), signedAt).abs()
                .compareTo(properties.getAllowedClockSkew()) > 0) {
            throw reject("OCCURRED_AT_INVALID", HttpStatus.UNAUTHORIZED, callback);
        }
        String payloadHash = sha256(body);
        String nonceHash = sha256(nonce.getBytes(StandardCharsets.US_ASCII));
        CallbackTraceContext traceContext = safeCallbackTraceContext(request);
        reserveNonce(nonceHash, callback);
        return Objects.requireNonNull(tx.execute(
                ignored -> process(callback, payloadHash, traceContext, request)));
    }

    public void auditRejected(CallbackRejectedException rejected, HttpServletRequest request) {
        audit.rejected(rejected.paymentNo(), rejected.outcome(), rejected.category(), request);
    }

    private void reserveNonce(String nonceHash, Callback callback) {
        try {
            nonceTx.executeWithoutResult(ignored -> jdbc.update(
                    "INSERT INTO payment_callback_nonce(nonce_hash,callback_id) VALUES(?,?)",
                    nonceHash, callback.callbackId()));
        } catch (DuplicateKeyException duplicate) {
            throw reject("NONCE_REPLAY", HttpStatus.CONFLICT, callback);
        }
    }

    private MockPaymentCallbackResponse process(Callback c, String payloadHash,
            CallbackTraceContext traceContext, HttpServletRequest request) {
        Ledger existing = jdbc.query("""
            SELECT payload_hash,business_result FROM payment_callback_ledger
             WHERE callback_id=? FOR UPDATE
            """, rs -> rs.next() ? new Ledger(rs.getString(1), rs.getString(2)) : null, c.callbackId());
        if (existing != null) {
            if (!existing.payloadHash().equals(payloadHash)) {
                throw reject("CALLBACK_ID_CONFLICT", HttpStatus.CONFLICT, c);
            }
            audit.accepted(c.paymentNo(), c.outcome(), "IDEMPOTENT", null, null, request);
            return new MockPaymentCallbackResponse(c.callbackId(), "IDEMPOTENT", true);
        }

        String orderId = jdbc.query("SELECT order_id FROM payment_order WHERE payment_no=? AND provider='MOCK'",
                rs -> rs.next() ? rs.getString(1) : null, c.paymentNo());
        if (orderId == null) throw reject("PAYMENT_FACT_CONFLICT", HttpStatus.CONFLICT, c);
        OrderFact order = jdbc.query("""
            SELECT order_id,total_amount,currency,status FROM sales_order WHERE order_id=? FOR UPDATE
            """, rs -> rs.next() ? new OrderFact(rs.getString(1), rs.getBigDecimal(2),
                    rs.getString(3), rs.getString(4)) : null, orderId);
        PaymentFact payment = jdbc.query("""
            SELECT payment_no,order_id,provider,amount,currency,status,provider_transaction_no
              FROM payment_order WHERE payment_no=? FOR UPDATE
            """, rs -> rs.next() ? new PaymentFact(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getBigDecimal(4), rs.getString(5), rs.getString(6), rs.getString(7)) : null,
                c.paymentNo());
        if (order == null || payment == null || !order.orderId().equals(payment.orderId())
                || !"MOCK".equals(payment.provider()) || c.amount().compareTo(payment.amount()) != 0
                || c.amount().compareTo(order.amount()) != 0 || !c.currency().equals(payment.currency())
                || !c.currency().equals(order.currency())) {
            throw reject("PAYMENT_FACT_CONFLICT", HttpStatus.CONFLICT, c);
        }
        Integer transactionConflict = jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_order
             WHERE provider='MOCK' AND provider_transaction_no=? AND payment_no<>?
            """, Integer.class, c.providerTransactionNo(), c.paymentNo());
        if (transactionConflict != null && transactionConflict > 0) {
            throw reject("PROVIDER_TRANSACTION_CONFLICT", HttpStatus.CONFLICT, c);
        }

        String previous = payment.status();
        String result;
        String next = previous;
        if ("FAILED".equals(c.outcome())) {
            if ("PENDING".equals(previous)) {
                requireOne(jdbc.update("UPDATE payment_order SET status='FAILED',version=version+1 WHERE payment_no=? AND status='PENDING'", c.paymentNo()));
                next = "FAILED";
                result = "PAYMENT_FAILED";
                insertOutbox(c, result, order.orderId(), traceContext);
                recordTimeline(order.orderId(), result, c.occurredAt(), traceContext);
            } else {
                result = "IDEMPOTENT";
            }
        } else if ("CANCELED".equals(order.status())) {
            if (!"LATE_SUCCEEDED".equals(previous)) {
                requireOne(jdbc.update("""
                    UPDATE payment_order SET status='LATE_SUCCEEDED',provider_transaction_no=?,paid_at=?,version=version+1
                     WHERE payment_no=? AND status IN ('PENDING','FAILED','CLOSED')
                    """, c.providerTransactionNo(), Timestamp.from(c.occurredAt()), c.paymentNo()));
                next = "LATE_SUCCEEDED";
                result = "PAYMENT_LATE_SUCCEEDED";
                insertOutbox(c, result, order.orderId(), traceContext);
                recordTimeline(order.orderId(), "LATE_SUCCEEDED", c.occurredAt(), traceContext);
            } else result = "IDEMPOTENT";
        } else if ("PENDING".equals(order.status()) && Set.of("PENDING", "FAILED").contains(previous)) {
            requireOne(jdbc.update("UPDATE sales_order SET status='PAID',paid_at=?,version=version+1 WHERE order_id=? AND status='PENDING'",
                    Timestamp.from(c.occurredAt()), order.orderId()));
            requireOne(jdbc.update("""
                UPDATE payment_order SET status='SUCCEEDED',provider_transaction_no=?,paid_at=?,version=version+1
                 WHERE payment_no=? AND status IN ('PENDING','FAILED')
                """, c.providerTransactionNo(), Timestamp.from(c.occurredAt()), c.paymentNo()));
            next = "SUCCEEDED";
            result = "PAYMENT_SUCCEEDED";
            insertOutbox(c, result, order.orderId(), traceContext);
            recordTimeline(order.orderId(), "PAID", c.occurredAt(), traceContext);
        } else if ("PAID".equals(order.status()) && "SUCCEEDED".equals(previous)) {
            result = "IDEMPOTENT";
        } else {
            throw reject("TERMINAL_FACT_CONFLICT", HttpStatus.CONFLICT, c);
        }

        jdbc.update("""
            INSERT INTO payment_callback_ledger(callback_id,payload_hash,provider,payment_no,
              provider_transaction_no,outcome,amount,currency,occurred_at,receive_result,
              business_result,previous_status,new_status)
            VALUES(?,?,'MOCK',?,?,?,?,?,?,'ACCEPTED',?,?,?)
            """, c.callbackId(), payloadHash, c.paymentNo(), c.providerTransactionNo(), c.outcome(),
                c.amount(), c.currency(), Timestamp.from(c.occurredAt()), result, previous, next);
        audit.accepted(c.paymentNo(), c.outcome(), result, previous, next, request);
        return new MockPaymentCallbackResponse(c.callbackId(), result, true);
    }

    private void insertOutbox(Callback c, String type, String orderId,
            CallbackTraceContext traceContext) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("paymentNo", c.paymentNo());
            payload.put("orderId", orderId);
            payload.put("provider", "MOCK");
            payload.put("outcome", c.outcome());
            payload.put("occurredAt", c.occurredAt().toString());
            payload.put("requestId", safeRequestId());
            payload.put("traceparent", traceContext.traceparent());
            payload.put("tracestate", traceContext.tracestate());
            String eventId = UUID.nameUUIDFromBytes(("hotshop/outbox/v1/" + type + "/" + c.paymentNo())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload)
                VALUES(?,'PAYMENT',?,?,CAST(? AS JSON))
                """, eventId, c.paymentNo(), type, json.writeValueAsString(payload));
        } catch (IOException exception) {
            throw new IllegalStateException("Payment event serialization failed", exception);
        }
    }

    private void recordTimeline(String orderId, String eventType, Instant occurredAt,
            CallbackTraceContext traceContext) {
        Long userId = jdbc.queryForObject(
                "SELECT user_id FROM sales_order WHERE order_id = ?", Long.class, orderId);
        if (userId == null) {
            throw new IllegalStateException("Payment Order owner is unavailable");
        }
        TransactionTimelineWriter.order(jdbc, userId, orderId, eventType, occurredAt,
                safeRequestId(), traceContext.traceparent(), traceContext.tracestate(),
                switch (eventType) {
                    case "PAYMENT_FAILED" -> "MOCK_PAYMENT_FAILED";
                    case "PAID" -> "MOCK_PAYMENT_CONFIRMED";
                    case "LATE_SUCCEEDED" -> "PAYMENT_ARRIVED_AFTER_CLOSE";
                    default -> "PAYMENT_STATE_CHANGED";
                });
    }

    private CallbackTraceContext safeCallbackTraceContext(HttpServletRequest request) {
        String traceparent;
        try {
            traceparent = AsyncTraceContext.currentTraceParent();
        } catch (RuntimeException observationFailure) {
            traceparent = "";
        }
        if (traceparent.isBlank() && request != null) {
            try {
                String raw = request.getHeader(AsyncTraceContext.TRACE_PARENT);
                traceparent = AsyncTraceContext.parse(raw).valid() ? raw : "";
            } catch (RuntimeException observationFailure) {
                traceparent = "";
            }
        }

        String tracestate;
        try {
            tracestate = AsyncTraceContext.currentTraceState();
        } catch (RuntimeException observationFailure) {
            tracestate = "";
        }
        if (tracestate.isBlank() && request != null) {
            try {
                tracestate = AsyncTraceContext.sanitizeTraceState(
                        request.getHeader(AsyncTraceContext.TRACE_STATE));
            } catch (RuntimeException observationFailure) {
                tracestate = "";
            }
        }
        return new CallbackTraceContext(traceparent, tracestate);
    }

    private Callback parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject() || root.size() != FIELDS.size()) throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
            Set<String> actual = new HashSet<>(); root.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
            String callbackId = text(root, "callbackId", 36);
            if (!UUID.fromString(callbackId).toString().equals(callbackId)) throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
            String paymentNo = text(root, "paymentNo", 64);
            String txn = text(root, "providerTransactionNo", 64);
            String outcome = text(root, "outcome", 9);
            String amountText = text(root, "amount", 20);
            String currency = text(root, "currency", 3);
            String occurredText = text(root, "occurredAt", 35);
            if (!PAYMENT_NO.matcher(paymentNo).matches() || !TRANSACTION_NO.matcher(txn).matches()
                    || !Set.of("SUCCEEDED", "FAILED").contains(outcome)
                    || !MONEY.matcher(amountText).matches() || !"CNY".equals(currency)) {
                throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
            }
            return new Callback(callbackId, paymentNo, txn, outcome, new BigDecimal(amountText), currency,
                    Instant.parse(occurredText));
        } catch (CallbackRejectedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
        }
    }

    private String text(JsonNode root, String field, int max) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) {
            throw reject("BODY_INVALID", HttpStatus.BAD_REQUEST);
        }
        return node.textValue();
    }
    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    private void requireOne(int changed) { if (changed != 1) throw new IllegalStateException("Conditional payment update lost"); }
    private String safeRequestId() {
        String value = MDC.get("requestId");
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$") ? value : "unknown";
    }
    private CallbackRejectedException reject(String category, HttpStatus status) { return new CallbackRejectedException(category, status); }
    private CallbackRejectedException reject(String category, HttpStatus status, Callback c) {
        return new CallbackRejectedException(category, status, c.paymentNo(), c.outcome());
    }
    private record Callback(String callbackId, String paymentNo, String providerTransactionNo,
                            String outcome, BigDecimal amount, String currency, Instant occurredAt) { }
    private record Ledger(String payloadHash, String businessResult) { }
    private record OrderFact(String orderId, BigDecimal amount, String currency, String status) { }
    private record PaymentFact(String paymentNo, String orderId, String provider, BigDecimal amount,
                               String currency, String status, String providerTransactionNo) { }
    private record CallbackTraceContext(String traceparent, String tracestate) {
    }
}
