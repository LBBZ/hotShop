package com.real.domain.adminops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.dto.AdminFlashSaleActivityResponse;
import com.real.common.api.dto.AdminManualReviewResponse;
import com.real.common.api.dto.AdminOperationsOverviewResponse;
import com.real.common.api.dto.AdminPaymentResponse;
import com.real.common.api.dto.AdminReconciliationIssueResponse;
import com.real.common.api.dto.AdminReconciliationStatusResponse;
import com.real.common.api.dto.OrderItemResponse;
import com.real.common.api.dto.OrderResponse;
import com.real.common.api.dto.ProductResponse;
import com.real.common.enums.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class AdminOperationsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminOperationsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public AdminOperationsOverviewResponse overview(Instant from, Instant to, Instant generatedAt) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        return new AdminOperationsOverviewResponse(
                from,
                to,
                generatedAt,
                "MySQL bounded by created_at/updated_at/last_seen_at",
                count("SELECT COUNT(*) FROM catalog_product WHERE created_at >= ? AND created_at < ?", start, end),
                count("SELECT COUNT(*) FROM flash_sale_activity WHERE created_at >= ? AND created_at < ?", start, end),
                count("SELECT COUNT(*) FROM sales_order WHERE created_at >= ? AND created_at < ?", start, end),
                count("SELECT COUNT(*) FROM sale_reservation WHERE created_at >= ? AND created_at < ?", start, end),
                count("SELECT COUNT(*) FROM payment_order WHERE created_at >= ? AND created_at < ?", start, end),
                count("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED' AND updated_at >= ? AND updated_at < ?", start, end),
                count("SELECT COUNT(*) FROM seckill_reconciliation_issue WHERE status='OPEN' AND last_seen_at >= ? AND last_seen_at < ?", start, end),
                count("SELECT COUNT(*) FROM seckill_event_processing WHERE status IN ('MANUAL_REVIEW','QUARANTINED') AND updated_at >= ? AND updated_at < ?", start, end)
        );
    }

    public List<AdminFlashSaleActivityResponse> activities(
            String status,
            Long productId,
            Long beforeActivityId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT activity_id,activity_code,product_id,sale_price,total_stock,
                       available_stock,per_user_limit,status,starts_at,ends_at,version,updated_at
                  FROM flash_sale_activity
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        add(sql, args, " AND status=?", status);
        add(sql, args, " AND product_id=?", productId);
        add(sql, args, " AND activity_id<?", beforeActivityId);
        sql.append(" ORDER BY activity_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::activity, args.toArray());
    }

    public List<ProductResponse> products(
            String keyword, String category, java.math.BigDecimal minPrice,
            java.math.BigDecimal maxPrice, Long afterId, int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT product_id,name,price,stock,category,description,created_at
                  FROM catalog_product
                 WHERE deleted_at IS NULL
                """);
        List<Object> args = new ArrayList<>();
        if (keyword != null) {
            sql.append(" AND name LIKE CONCAT('%',?,'%')");
            args.add(keyword);
        }
        add(sql, args, " AND category=?", category);
        add(sql, args, " AND price>=?", minPrice);
        add(sql, args, " AND price<=?", maxPrice);
        add(sql, args, " AND product_id>?", afterId);
        sql.append(" ORDER BY product_id ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new ProductResponse(
                rs.getLong("product_id"), rs.getString("name"),
                rs.getBigDecimal("price").setScale(2, RoundingMode.UNNECESSARY),
                rs.getInt("stock"), rs.getString("category"), rs.getString("description"),
                instant(rs.getTimestamp("created_at"))
        ), args.toArray());
    }

    public List<OrderResponse> orders(
            Long userId, String status, Instant from, Instant to,
            LocalDateTime beforeTime, String beforeOrderId, int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT order_id,user_id,total_amount,currency,status,created_at
                  FROM sales_order
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        add(sql, args, " AND user_id=?", userId);
        add(sql, args, " AND status=?", status);
        add(sql, args, " AND created_at>=?", timestamp(from));
        add(sql, args, " AND created_at<=?", timestamp(to));
        if (beforeTime != null && beforeOrderId != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND order_id<?))");
            Timestamp time = Timestamp.valueOf(beforeTime);
            args.add(time);
            args.add(time);
            args.add(beforeOrderId);
        }
        sql.append(" ORDER BY created_at DESC,order_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new OrderResponse(
                rs.getString("order_id"), rs.getLong("user_id"),
                rs.getBigDecimal("total_amount").setScale(2, RoundingMode.UNNECESSARY),
                rs.getString("currency"), OrderStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("created_at")), orderItems(rs.getString("order_id"))
        ), args.toArray());
    }

    public List<AdminPaymentResponse> payments(
            String status,
            String orderId,
            Instant from,
            Instant to,
            LocalDateTime beforeTime,
            Long beforeId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT payment_id,payment_no,order_id,provider,amount,currency,status,
                       expires_at,paid_at,created_at,updated_at
                  FROM payment_order
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        add(sql, args, " AND status=?", status);
        add(sql, args, " AND order_id=?", orderId);
        add(sql, args, " AND created_at>=?", timestamp(from));
        add(sql, args, " AND created_at<=?", timestamp(to));
        if (beforeTime != null && beforeId != null) {
            sql.append(" AND (created_at<? OR (created_at=? AND payment_id<?))");
            Timestamp time = Timestamp.valueOf(beforeTime);
            args.add(time);
            args.add(time);
            args.add(beforeId);
        }
        sql.append(" ORDER BY created_at DESC,payment_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::payment, args.toArray());
    }

    public List<AdminReconciliationIssueResponse> issues(
            String status,
            String severity,
            Long activityId,
            String reservationNo,
            LocalDateTime beforeTime,
            Long beforeId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT issue_id,issue_type,severity,status,activity_id,reservation_no,
                       occurrences,evidence_version,evidence_summary,first_seen_at,last_seen_at
                  FROM seckill_reconciliation_issue
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        add(sql, args, " AND status=?", status);
        add(sql, args, " AND severity=?", severity);
        add(sql, args, " AND activity_id=?", activityId);
        add(sql, args, " AND reservation_no=?", reservationNo);
        if (beforeTime != null && beforeId != null) {
            sql.append(" AND (last_seen_at<? OR (last_seen_at=? AND issue_id<?))");
            Timestamp time = Timestamp.valueOf(beforeTime);
            args.add(time);
            args.add(time);
            args.add(beforeId);
        }
        sql.append(" ORDER BY last_seen_at DESC,issue_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::issue, args.toArray());
    }

    public List<AdminManualReviewResponse> manualReviews(
            LocalDateTime beforeTime,
            Long beforeId,
            int limit
    ) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT processing_id,event_id,reservation_no,activity_id,status,attempts,
                       reason_code,last_error,updated_at
                  FROM seckill_event_processing
                 WHERE status IN ('MANUAL_REVIEW','QUARANTINED')
                """);
        if (beforeTime != null && beforeId != null) {
            sql.append(" AND (updated_at<? OR (updated_at=? AND processing_id<?))");
            Timestamp time = Timestamp.valueOf(beforeTime);
            args.add(time);
            args.add(time);
            args.add(beforeId);
        }
        sql.append(" ORDER BY updated_at DESC,processing_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::manualReview, args.toArray());
    }

    public AdminReconciliationStatusResponse reconciliationStatus(Boolean dryRun, Boolean autoRepair) {
        Timestamp checkpoint = jdbc.queryForObject(
                "SELECT MAX(updated_at) FROM seckill_reconciliation_checkpoint",
                Timestamp.class
        );
        long open = count("SELECT COUNT(*) FROM seckill_reconciliation_issue WHERE status='OPEN'");
        long critical = count("SELECT COUNT(*) FROM seckill_reconciliation_issue WHERE status='OPEN' AND severity='CRITICAL'");
        return new AdminReconciliationStatusResponse(
                dryRun,
                autoRepair,
                instant(checkpoint),
                open,
                critical,
                Boolean.TRUE.equals(autoRepair)
                        ? "Task configuration reports auto-repair enabled; findings do not prove that a repair completed."
                        : dryRun == null || autoRepair == null
                                ? "Task run mode is not persisted; only findings and checkpoints are shown, and no repair is claimed."
                                : "Findings are evidence only; no automatic repair is reported or implied."
        );
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private List<OrderItemResponse> orderItems(String orderId) {
        return jdbc.query("""
                SELECT order_item_id,product_id,quantity,price,line_amount
                  FROM sales_order_item WHERE order_id=? ORDER BY order_item_id
                """, (rs, row) -> new OrderItemResponse(
                rs.getLong("order_item_id"), rs.getLong("product_id"), rs.getInt("quantity"),
                rs.getBigDecimal("price").setScale(2, RoundingMode.UNNECESSARY),
                rs.getBigDecimal("line_amount").setScale(2, RoundingMode.UNNECESSARY)
        ), orderId);
    }

    private AdminFlashSaleActivityResponse activity(ResultSet rs, int row) throws SQLException {
        return new AdminFlashSaleActivityResponse(
                rs.getLong("activity_id"), rs.getString("activity_code"), rs.getLong("product_id"),
                rs.getBigDecimal("sale_price").setScale(2, RoundingMode.UNNECESSARY),
                rs.getInt("total_stock"), rs.getInt("available_stock"), rs.getInt("per_user_limit"),
                rs.getString("status"), instant(rs.getTimestamp("starts_at")),
                instant(rs.getTimestamp("ends_at")), rs.getInt("version"),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private AdminPaymentResponse payment(ResultSet rs, int row) throws SQLException {
        return new AdminPaymentResponse(
                rs.getLong("payment_id"), rs.getString("payment_no"), rs.getString("order_id"),
                rs.getString("provider"), rs.getBigDecimal("amount").setScale(2, RoundingMode.UNNECESSARY),
                rs.getString("currency"), rs.getString("status"), instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("paid_at")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private AdminReconciliationIssueResponse issue(ResultSet rs, int row) throws SQLException {
        Long activityId = rs.getObject("activity_id", Long.class);
        return new AdminReconciliationIssueResponse(
                rs.getLong("issue_id"), rs.getString("issue_type"), rs.getString("severity"),
                rs.getString("status"), activityId, rs.getString("reservation_no"),
                rs.getInt("occurrences"), rs.getInt("evidence_version"),
                json(rs.getString("evidence_summary")), instant(rs.getTimestamp("first_seen_at")),
                instant(rs.getTimestamp("last_seen_at"))
        );
    }

    private AdminManualReviewResponse manualReview(ResultSet rs, int row) throws SQLException {
        Long activityId = rs.getObject("activity_id", Long.class);
        return new AdminManualReviewResponse(
                rs.getLong("processing_id"), rs.getString("event_id"), rs.getString("reservation_no"),
                activityId, rs.getString("status"), rs.getInt("attempts"),
                rs.getString("reason_code"), rs.getString("last_error"),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private Map<String, Object> json(String value) throws SQLException {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored reconciliation evidence is invalid JSON", exception);
        }
    }

    private void add(StringBuilder sql, List<Object> args, String predicate, Object value) {
        if (value != null) {
            sql.append(predicate);
            args.add(value);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
