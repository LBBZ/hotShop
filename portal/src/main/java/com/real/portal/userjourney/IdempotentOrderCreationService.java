package com.real.portal.userjourney;

import com.real.common.api.ApiException;
import com.real.common.api.dto.CreateOrderRequest;
import com.real.common.enums.OrderStatus;
import com.real.common.observability.AsyncTraceContext;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Order;
import com.real.domain.service.advance.OrderStateService;
import com.real.domain.userjourney.TransactionTimelineWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class IdempotentOrderCreationService {
    private final JdbcTemplate jdbc;
    private final OrderStateService orderStateService;

    public IdempotentOrderCreationService(JdbcTemplate jdbc, OrderStateService orderStateService) {
        this.jdbc = jdbc;
        this.orderStateService = orderStateService;
    }

    @Transactional
    public Result create(
            long userId,
            CreateOrderRequest request,
            String idempotencyKey,
            String requestId
    ) {
        String keyHash = sha256(idempotencyKey);
        String fingerprint = fingerprint(request);
        int inserted;
        try {
            inserted = jdbc.update("""
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, request_id, status
                    ) VALUES (?, ?, ?, ?, 'PROCESSING')
                    """, userId, keyHash, fingerprint, requestId);
        } catch (DuplicateKeyException exception) {
            Integer expectedIntent = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM order_purchase_intent
                     WHERE user_id = ? AND idempotency_key_hash = ?
                    """, Integer.class, userId, keyHash);
            if (expectedIntent == null || expectedIntent != 1) {
                throw exception;
            }
            inserted = 0;
        }

        if (inserted == 0) {
            return jdbc.query("""
                    SELECT request_fingerprint, order_id, request_id
                      FROM order_purchase_intent
                     WHERE user_id = ? AND idempotency_key_hash = ?
                    """, rs -> {
                if (!rs.next()) {
                    throw ApiException.serviceUnavailable(
                            "ORDER_IDEMPOTENCY_UNAVAILABLE",
                            "The purchase intent could not be recovered"
                    );
                }
                if (!fingerprint.equals(rs.getString("request_fingerprint"))) {
                    throw ApiException.conflict(
                            "IDEMPOTENCY_KEY_CONFLICT",
                            "Idempotency-Key is already bound to a different order request"
                    );
                }
                String orderId = rs.getString("order_id");
                if (orderId == null) {
                    throw ApiException.conflict(
                            "ORDER_INTENT_IN_PROGRESS",
                            "The original purchase intent is still being processed"
                    );
                }
                return new Result(orderId, OrderStatus.PENDING, rs.getString("request_id"), true);
            }, userId, keyHash);
        }

        Order order = ApiDtoMapper.toOrder(request);
        order.setUserId(userId);
        String traceparent = AsyncTraceContext.currentTraceParent();
        String tracestate = AsyncTraceContext.currentTraceState();
        String orderId = orderStateService.createOrder(order, requestId, traceparent, tracestate);
        TransactionTimelineWriter.order(jdbc, userId, orderId, "ORDER_CREATED",
                java.time.Instant.now(), requestId, traceparent, tracestate,
                "ORDER_DURABLY_CREATED");
        jdbc.update("""
                UPDATE order_purchase_intent
                   SET order_id = ?, status = 'ORDER_CREATED'
                 WHERE user_id = ? AND idempotency_key_hash = ?
                """, orderId, userId, keyHash);
        return new Result(orderId, OrderStatus.PENDING, requestId, false);
    }

    private static String fingerprint(CreateOrderRequest request) {
        String canonical = request.items().stream()
                .map(item -> item.productId() + ":" + item.quantity())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return sha256(canonical);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Result(String orderId, OrderStatus status, String requestId, boolean replayed) { }
}
