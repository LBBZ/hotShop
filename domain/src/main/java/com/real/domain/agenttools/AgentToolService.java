package com.real.domain.agenttools;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.AgentOrderListResponse;
import com.real.common.api.dto.AgentOrderSummaryResponse;
import com.real.common.api.dto.AgentProductComparisonResponse;
import com.real.common.api.dto.AgentProductSearchResponse;
import com.real.common.api.dto.AgentProductSummaryResponse;
import com.real.common.api.dto.AgentReservationListResponse;
import com.real.common.api.dto.AgentReservationSummaryResponse;
import com.real.common.api.dto.PurchaseDraftCreateRequest;
import com.real.common.api.dto.PurchaseDraftItemResponse;
import com.real.common.api.dto.PurchaseDraftResponse;
import com.real.domain.entity.Order;
import com.real.domain.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentToolService {
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final AgentToolAuditWriter audit;
    private final Duration draftTtl;

    public AgentToolService(
            JdbcTemplate jdbc,
            OrderService orders,
            AgentToolAuditWriter audit,
            @Value("${hotshop.agent.purchase-draft-ttl:10m}") Duration draftTtl
    ) {
        if (draftTtl == null || draftTtl.isZero() || draftTtl.isNegative()
                || draftTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Purchase Draft TTL must be between 1 ms and 1 hour");
        }
        this.jdbc = jdbc;
        this.orders = orders;
        this.audit = audit;
        this.draftTtl = draftTtl;
    }

    @Transactional(readOnly = true)
    public AgentProductSearchResponse searchProducts(
            String keyword,
            int limit,
            String cursor
    ) {
        Long after = parseCursor(cursor);
        List<AgentProductSummaryResponse> fetched = jdbc.query("""
                SELECT product_id, name, price, category, description, stock
                  FROM catalog_product
                 WHERE status = 'ACTIVE' AND deleted_at IS NULL
                   AND (? IS NULL OR name LIKE CONCAT('%', ?, '%'))
                   AND (? IS NULL OR product_id > ?)
                 ORDER BY product_id ASC
                 LIMIT ?
                """, (rs, row) -> product(
                rs.getLong("product_id"), rs.getString("name"), rs.getBigDecimal("price"),
                rs.getString("category"), rs.getString("description"), rs.getInt("stock")
        ), keyword, keyword, after, after, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<AgentProductSummaryResponse> items = hasMore
                ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String next = hasMore ? Long.toString(items.get(items.size() - 1).productId()) : null;
        return new AgentProductSearchResponse(items, next, hasMore);
    }

    @Transactional(readOnly = true)
    public AgentProductSummaryResponse getProduct(long productId) {
        List<AgentProductSummaryResponse> result = productsByIds(List.of(productId));
        if (result.size() != 1) {
            throw ApiException.notFound("Catalog Product");
        }
        return result.get(0);
    }

    @Transactional(readOnly = true)
    public AgentProductComparisonResponse compareProducts(List<Long> productIds) {
        if (productIds == null || productIds.size() < 2 || productIds.size() > 10
                || productIds.stream().distinct().count() != productIds.size()) {
            throw ApiException.badRequest(
                    "PRODUCT_COMPARISON_INVALID",
                    "Comparison requires 2-10 unique Product IDs"
            );
        }
        List<AgentProductSummaryResponse> result = productsByIds(productIds);
        if (result.size() != productIds.size()) {
            throw ApiException.notFound("Catalog Product");
        }
        Map<Long, AgentProductSummaryResponse> byId = new HashMap<>();
        result.forEach(item -> byId.put(item.productId(), item));
        return new AgentProductComparisonResponse(productIds.stream().map(byId::get).toList());
    }

    @Transactional(readOnly = true)
    public AgentOrderListResponse listOrders(long userId, int limit, String cursor) {
        CursorSlice<Order> slice = orders.getUserOrdersByCursor(
                userId, limit, cursor, null, null, null
        );
        return new AgentOrderListResponse(
                slice.items().stream().map(order -> new AgentOrderSummaryResponse(
                        order.getOrderId(), money(order.getTotalAmount()), "CNY",
                        order.getStatus().name(), order.getCreatedAt().toInstant(ZoneOffset.UTC),
                        order.getItems() == null ? 0 : order.getItems().size()
                )).toList(),
                slice.nextCursor(),
                slice.hasMore()
        );
    }

    @Transactional(readOnly = true)
    public AgentReservationListResponse listReservations(long userId, int limit) {
        return new AgentReservationListResponse(jdbc.query("""
                SELECT reservation_no, activity_id, product_id, quantity, reserved_amount,
                       COALESCE(currency, 'CNY') currency, status, order_id, created_at
                  FROM sale_reservation
                 WHERE user_id = ?
                 ORDER BY created_at DESC, reservation_id DESC
                 LIMIT ?
                """, (rs, row) -> new AgentReservationSummaryResponse(
                rs.getString("reservation_no"), rs.getLong("activity_id"),
                rs.getLong("product_id"), rs.getInt("quantity"),
                money(rs.getBigDecimal("reserved_amount")), rs.getString("currency"),
                rs.getString("status"), rs.getString("order_id"),
                rs.getTimestamp("created_at").toInstant()
        ), userId, limit));
    }

    @Transactional
    public PurchaseDraftResponse createDraft(
            long userId,
            String agentClientId,
            PurchaseDraftCreateRequest request,
            HttpServletRequest servletRequest
    ) {
        List<PurchaseParameters.Item> requested = PurchaseParameters.normalize(request.items());
        List<Long> productIds = requested.stream().map(PurchaseParameters.Item::productId).toList();
        List<AgentProductSummaryResponse> current = productsByIds(productIds);
        if (current.size() != requested.size()) {
            throw ApiException.notFound("Catalog Product");
        }
        Map<Long, AgentProductSummaryResponse> products = new HashMap<>();
        current.forEach(product -> products.put(product.productId(), product));
        if (current.stream().anyMatch(product -> !product.available())) {
            throw ApiException.conflict("PRODUCT_UNAVAILABLE", "A Product is currently unavailable");
        }

        String draftId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant validUntil = now.plus(draftTtl);
        String digest = PurchaseParameters.digest(requested);
        jdbc.update("""
                INSERT INTO purchase_draft (
                    draft_id, user_id, action_type, parameter_digest, status,
                    valid_until, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, draftId, userId, PurchaseParameters.ACTION, digest,
                Timestamp.from(validUntil), Timestamp.from(now), Timestamp.from(now));

        List<PurchaseDraftItemResponse> snapshots = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (PurchaseParameters.Item item : requested) {
            AgentProductSummaryResponse product = products.get(item.productId());
            BigDecimal unit = money(product.price());
            BigDecimal line = money(unit.multiply(BigDecimal.valueOf(item.quantity())));
            total = total.add(line);
            jdbc.update("""
                    INSERT INTO purchase_draft_item (
                        draft_id, product_id, quantity, product_name_snapshot,
                        unit_price_snapshot, line_amount_snapshot, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, draftId, item.productId(), item.quantity(), product.name(),
                    unit, line, Timestamp.from(now));
            snapshots.add(new PurchaseDraftItemResponse(
                    item.productId(), product.name(), item.quantity(), unit, line, "CNY"
            ));
        }
        PurchaseDraftResponse response = new PurchaseDraftResponse(
                draftId,
                PurchaseParameters.ACTION,
                snapshots,
                money(total),
                "CNY",
                validUntil,
                true,
                "Review this snapshot and explicitly request a short-lived confirmation token"
        );
        audit.appendAgentTool(
                agentClientId,
                userId,
                "create_purchase_draft",
                "PURCHASE_DRAFT",
                draftId,
                "SUCCESS",
                Map.of(
                        "schemaVersion", 1,
                        "itemCount", requested.size(),
                        "parameterDigest", digest
                ),
                servletRequest
        );
        return response;
    }

    private List<AgentProductSummaryResponse> productsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("""
                SELECT product_id, name, price, category, description, stock
                  FROM catalog_product
                 WHERE status = 'ACTIVE' AND deleted_at IS NULL
                   AND product_id IN (%s)
                 ORDER BY product_id ASC
                """.formatted(placeholders), (rs, row) -> product(
                rs.getLong("product_id"), rs.getString("name"), rs.getBigDecimal("price"),
                rs.getString("category"), rs.getString("description"), rs.getInt("stock")
        ), ids.toArray());
    }

    private static AgentProductSummaryResponse product(
            long id, String name, BigDecimal price, String category, String description, int stock
    ) {
        return new AgentProductSummaryResponse(
                id, name, money(price), "CNY", category, description, stock > 0
        );
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(cursor);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("CURSOR_INVALID", "Cursor is invalid");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
