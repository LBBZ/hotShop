package com.real.domain.agenttools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.ApiException;
import com.real.common.api.dto.PurchaseConfirmationConsumeRequest;
import com.real.common.api.dto.PurchaseConfirmationIssueResponse;
import com.real.common.api.dto.PurchaseConfirmationOrderResponse;
import com.real.common.enums.OrderStatus;
import com.real.common.observability.AsyncTraceContext;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.service.advance.OrderStateService;
import com.real.domain.userjourney.TransactionTimelineWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PurchaseConfirmationService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrderStateService orderStateService;
    private final AgentToolAuditWriter audit;
    private final Duration confirmationTtl;

    public PurchaseConfirmationService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            OrderStateService orderStateService,
            AgentToolAuditWriter audit,
            @Value("${hotshop.agent.purchase-confirmation-ttl:2m}") Duration confirmationTtl
    ) {
        if (confirmationTtl == null || confirmationTtl.isZero() || confirmationTtl.isNegative()
                || confirmationTtl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Purchase Confirmation TTL must be at most 5 minutes");
        }
        this.jdbc = jdbc;
        this.json = json;
        this.orderStateService = orderStateService;
        this.audit = audit;
        this.confirmationTtl = confirmationTtl;
    }

    @Transactional
    public PurchaseConfirmationIssueResponse issue(
            long userId,
            String draftId,
            String actionType,
            HttpServletRequest request
    ) {
        if (!PurchaseParameters.ACTION.equals(actionType)) {
            throw confirmationRejected("PURCHASE_ACTION_INVALID", "Purchase action is invalid");
        }
        Draft draft = lockDraft(draftId);
        Instant now = Instant.now();
        if (draft == null || draft.userId() != userId) {
            throw confirmationRejected("PURCHASE_DRAFT_INVALID", "Purchase Draft is unavailable");
        }
        if (!PurchaseParameters.ACTION.equals(draft.actionType())
                || !"ACTIVE".equals(draft.status())
                || !draft.validUntil().isAfter(now)) {
            throw confirmationRejected("PURCHASE_DRAFT_INVALID", "Purchase Draft is unavailable");
        }

        List<PurchaseParameters.Item> items = draftItems(draftId);
        String digest = PurchaseParameters.digest(items);
        if (!digest.equals(draft.parameterDigest())) {
            throw ApiException.serviceUnavailable(
                    "PURCHASE_CONFIRMATION_UNAVAILABLE",
                    "Purchase confirmation is temporarily unavailable"
            );
        }

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = PurchaseParameters.sha256(token);
        String confirmationId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(confirmationTtl);
        jdbc.update("""
                INSERT INTO purchase_confirmation (
                    confirmation_id, token_hash, draft_id, user_id, action_type,
                    parameter_digest, parameters_json, nonce, status,
                    issued_at, expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?)
                """, confirmationId, tokenHash, draftId, userId, PurchaseParameters.ACTION,
                digest, parametersJson(items), nonce, Timestamp.from(now),
                Timestamp.from(expiresAt), Timestamp.from(now));
        jdbc.update("""
                UPDATE purchase_draft
                   SET status = 'CONFIRMATION_ISSUED', updated_at = ?
                 WHERE draft_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), draftId);
        audit.appendConfirmation(
                userId,
                "PURCHASE_CONFIRMATION_ISSUED",
                "PURCHASE_CONFIRMATION",
                confirmationId,
                "SUCCESS",
                Map.of(
                        "schemaVersion", 1,
                        "actionType", PurchaseParameters.ACTION,
                        "draftId", draftId,
                        "itemCount", items.size(),
                        "parameterDigest", digest
                ),
                request
        );
        return new PurchaseConfirmationIssueResponse(
                draftId, PurchaseParameters.ACTION, token, expiresAt
        );
    }

    @Transactional
    public PurchaseConfirmationOrderResponse consume(
            long userId,
            PurchaseConfirmationConsumeRequest requestBody,
            String requestId,
            HttpServletRequest request
    ) {
        String suppliedHash = PurchaseParameters.sha256(requestBody.confirmationToken());
        Confirmation confirmation = lockConfirmation(suppliedHash);
        Instant now = Instant.now();
        if (confirmation == null
                || confirmation.userId() != userId
                || !confirmation.draftId().equals(requestBody.draftId())
                || !confirmation.actionType().equals(requestBody.actionType())
                || !PurchaseParameters.ACTION.equals(requestBody.actionType())
                || !"ISSUED".equals(confirmation.status())
                || !confirmation.expiresAt().isAfter(now)) {
            throw confirmationRejected(
                    "PURCHASE_CONFIRMATION_INVALID",
                    "Purchase confirmation is invalid, expired, revoked, or already used"
            );
        }

        List<PurchaseParameters.Item> requested = PurchaseParameters.normalize(requestBody.items());
        String digest = PurchaseParameters.digest(requested);
        if (!digest.equals(confirmation.parameterDigest())) {
            throw confirmationRejected(
                    "PURCHASE_CONFIRMATION_MISMATCH",
                    "Purchase parameters do not match the confirmed Purchase Draft"
            );
        }

        String orderId = UUID.randomUUID().toString();
        int consumed = jdbc.update("""
                UPDATE purchase_confirmation
                   SET status = 'CONSUMED', consumed_at = ?, order_id = ?, updated_at = ?
                 WHERE confirmation_id = ? AND status = 'ISSUED'
                """, Timestamp.from(now), orderId, Timestamp.from(now), confirmation.confirmationId());
        if (consumed != 1) {
            throw confirmationRejected(
                    "PURCHASE_CONFIRMATION_INVALID",
                    "Purchase confirmation is invalid, expired, revoked, or already used"
            );
        }

        Order order = new Order();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);
        order.setItems(requested.stream().map(item -> {
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(item.productId());
            orderItem.setQuantity(item.quantity());
            return orderItem;
        }).toList());
        String traceparent = AsyncTraceContext.currentTraceParent();
        String tracestate = AsyncTraceContext.currentTraceState();
        orderStateService.createOrder(order, requestId, traceparent, tracestate);
        TransactionTimelineWriter.order(
                jdbc, userId, orderId, "ORDER_CREATED", now, requestId,
                traceparent, tracestate, "CONFIRMED_AGENT_PURCHASE_CREATED"
        );
        audit.appendConfirmation(
                userId,
                "PURCHASE_CONFIRMATION_CONSUMED",
                "SALES_ORDER",
                orderId,
                "SUCCESS",
                Map.of(
                        "schemaVersion", 1,
                        "actionType", PurchaseParameters.ACTION,
                        "draftId", confirmation.draftId(),
                        "itemCount", requested.size(),
                        "parameterDigest", digest
                ),
                request
        );
        return new PurchaseConfirmationOrderResponse(
                orderId, OrderStatus.PENDING.name(), confirmation.draftId(), now
        );
    }

    @Transactional
    public void revoke(long userId, String draftId, HttpServletRequest request) {
        Confirmation confirmation = lockConfirmationByDraft(draftId);
        if (confirmation == null || confirmation.userId() != userId
                || !"ISSUED".equals(confirmation.status())) {
            throw confirmationRejected(
                    "PURCHASE_CONFIRMATION_INVALID",
                    "Purchase confirmation is invalid, expired, revoked, or already used"
            );
        }
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE purchase_confirmation
                   SET status = 'REVOKED', revoked_at = ?, updated_at = ?
                 WHERE confirmation_id = ? AND status = 'ISSUED'
                """, Timestamp.from(now), Timestamp.from(now), confirmation.confirmationId());
        jdbc.update("""
                UPDATE purchase_draft
                   SET status = 'CANCELLED', updated_at = ?
                 WHERE draft_id = ?
                """, Timestamp.from(now), draftId);
        audit.appendConfirmation(
                userId,
                "PURCHASE_CONFIRMATION_REVOKED",
                "PURCHASE_CONFIRMATION",
                confirmation.confirmationId(),
                "SUCCESS",
                Map.of("schemaVersion", 1, "draftId", draftId),
                request
        );
    }

    private Draft lockDraft(String draftId) {
        List<Draft> drafts = jdbc.query("""
                SELECT draft_id, user_id, action_type, parameter_digest, status, valid_until
                  FROM purchase_draft
                 WHERE draft_id = ?
                   FOR UPDATE
                """, (rs, row) -> new Draft(
                rs.getString("draft_id"), rs.getLong("user_id"),
                rs.getString("action_type"), rs.getString("parameter_digest"),
                rs.getString("status"), rs.getTimestamp("valid_until").toInstant()
        ), draftId);
        return drafts.size() == 1 ? drafts.get(0) : null;
    }

    private Confirmation lockConfirmation(String tokenHash) {
        return lockConfirmationWhere("token_hash", tokenHash);
    }

    private Confirmation lockConfirmationByDraft(String draftId) {
        return lockConfirmationWhere("draft_id", draftId);
    }

    private Confirmation lockConfirmationWhere(String column, String value) {
        if (!column.equals("token_hash") && !column.equals("draft_id")) {
            throw new IllegalArgumentException("Unsupported confirmation lookup");
        }
        List<Confirmation> records = jdbc.query("""
                SELECT confirmation_id, draft_id, user_id, action_type, parameter_digest,
                       CAST(parameters_json AS CHAR) parameters_json, status, expires_at
                  FROM purchase_confirmation
                 WHERE %s = ?
                   FOR UPDATE
                """.formatted(column), (rs, row) -> new Confirmation(
                rs.getString("confirmation_id"), rs.getString("draft_id"),
                rs.getLong("user_id"), rs.getString("action_type"),
                rs.getString("parameter_digest"), rs.getString("parameters_json"),
                rs.getString("status"), rs.getTimestamp("expires_at").toInstant()
        ), value);
        return records.size() == 1 ? records.get(0) : null;
    }

    private List<PurchaseParameters.Item> draftItems(String draftId) {
        return jdbc.query("""
                SELECT product_id, quantity
                  FROM purchase_draft_item
                 WHERE draft_id = ?
                 ORDER BY product_id ASC
                """, (rs, row) -> new PurchaseParameters.Item(
                rs.getLong("product_id"), rs.getInt("quantity")
        ), draftId);
    }

    private String parametersJson(List<PurchaseParameters.Item> items) {
        List<Map<String, Object>> parameters = items.stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("productId", item.productId());
            value.put("quantity", item.quantity());
            return value;
        }).toList();
        try {
            return json.writeValueAsString(Map.of("items", parameters));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Purchase parameters", exception);
        }
    }

    private ApiException confirmationRejected(String code, String detail) {
        return ApiException.conflict(code, detail);
    }

    private record Draft(
            String draftId,
            long userId,
            String actionType,
            String parameterDigest,
            String status,
            Instant validUntil
    ) { }

    private record Confirmation(
            String confirmationId,
            String draftId,
            long userId,
            String actionType,
            String parameterDigest,
            String parametersJson,
            String status,
            Instant expiresAt
    ) { }
}
