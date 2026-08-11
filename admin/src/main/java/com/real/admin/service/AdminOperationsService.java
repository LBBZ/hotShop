package com.real.admin.service;

import com.real.common.api.CursorSlice;
import com.real.common.api.dto.AdminFlashSaleActivityResponse;
import com.real.common.api.dto.AdminManualReviewResponse;
import com.real.common.api.dto.AdminOperationsOverviewResponse;
import com.real.common.api.dto.AdminPaymentResponse;
import com.real.common.api.dto.AdminReconciliationIssueResponse;
import com.real.common.api.dto.AdminReconciliationStatusResponse;
import com.real.common.api.dto.OrderResponse;
import com.real.common.api.dto.ProductResponse;
import com.real.domain.adminops.AdminOperationsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;

@Service
public class AdminOperationsService {
    private final AdminOperationsRepository repository;
    private final AdminCursorCodec cursors;
    private final Boolean reconciliationDryRun;
    private final Boolean reconciliationAutoRepair;

    public AdminOperationsService(
            AdminOperationsRepository repository,
            AdminCursorCodec cursors,
            @Value("${HOTSHOP_SECKILL_RECONCILIATION_DRY_RUN:}") String dryRun,
            @Value("${HOTSHOP_SECKILL_RECONCILIATION_AUTO_REPAIR:}") String autoRepair
    ) {
        this.repository = repository;
        this.cursors = cursors;
        this.reconciliationDryRun = optionalBoolean(dryRun);
        this.reconciliationAutoRepair = optionalBoolean(autoRepair);
    }

    public AdminOperationsOverviewResponse overview(int windowHours) {
        Instant to = Instant.now();
        return repository.overview(to.minusSeconds(windowHours * 3600L), to, to);
    }

    public CursorSlice<AdminFlashSaleActivityResponse> activities(
            int limit, String cursor, String status, Long productId
    ) {
        String scope = scope("activities", status, productId);
        AdminCursorCodec.LongCursor decoded = cursors.decodeLong(cursor, scope);
        List<AdminFlashSaleActivityResponse> fetched = repository.activities(
                status, productId, decoded == null ? null : decoded.id(), limit + 1
        );
        boolean more = fetched.size() > limit;
        List<AdminFlashSaleActivityResponse> items = page(fetched, limit, more);
        String next = more ? cursors.encodeLong(scope, items.getLast().activityId()) : null;
        return new CursorSlice<>(items, next, more);
    }

    public CursorSlice<ProductResponse> products(
            int limit, String cursor, String keyword, String category,
            BigDecimal minPrice, BigDecimal maxPrice
    ) {
        String scope = scope("products", keyword, category, minPrice, maxPrice);
        AdminCursorCodec.LongCursor decoded = cursors.decodeLong(cursor, scope);
        List<ProductResponse> fetched = repository.products(
                keyword, category, minPrice, maxPrice,
                decoded == null ? null : decoded.id(), limit + 1
        );
        boolean more = fetched.size() > limit;
        List<ProductResponse> items = page(fetched, limit, more);
        String next = more ? cursors.encodeLong(scope, items.getLast().productId()) : null;
        return new CursorSlice<>(items, next, more);
    }

    public CursorSlice<OrderResponse> orders(
            int limit, String cursor, Long userId, String status,
            Instant createdFrom, Instant createdTo
    ) {
        String scope = scope("orders", userId, status, createdFrom, createdTo);
        AdminCursorCodec.TimeStringCursor decoded = cursors.decodeTimeString(cursor, scope);
        List<OrderResponse> fetched = repository.orders(
                userId, status, createdFrom, createdTo,
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(), limit + 1
        );
        boolean more = fetched.size() > limit;
        List<OrderResponse> items = page(fetched, limit, more);
        OrderResponse last = more ? items.getLast() : null;
        String next = more ? cursors.encodeTimeString(
                scope, LocalDateTime.ofInstant(last.createdAt(), ZoneOffset.UTC), last.orderId()
        ) : null;
        return new CursorSlice<>(items, next, more);
    }

    public CursorSlice<AdminPaymentResponse> payments(
            int limit,
            String cursor,
            String status,
            String orderId,
            Instant createdFrom,
            Instant createdTo
    ) {
        String scope = scope("payments", status, orderId, createdFrom, createdTo);
        AdminCursorCodec.TimeLongCursor decoded = cursors.decodeTimeLong(cursor, scope);
        List<AdminPaymentResponse> fetched = repository.payments(
                status,
                orderId,
                createdFrom,
                createdTo,
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(),
                limit + 1
        );
        boolean more = fetched.size() > limit;
        List<AdminPaymentResponse> items = page(fetched, limit, more);
        AdminPaymentResponse last = more ? items.getLast() : null;
        String next = more ? cursors.encodeTimeLong(
                scope, LocalDateTime.ofInstant(last.createdAt(), ZoneOffset.UTC), last.paymentId()
        ) : null;
        return new CursorSlice<>(items, next, more);
    }

    public CursorSlice<AdminReconciliationIssueResponse> issues(
            int limit,
            String cursor,
            String status,
            String severity,
            Long activityId,
            String reservationNo
    ) {
        String scope = scope("issues", status, severity, activityId, reservationNo);
        AdminCursorCodec.TimeLongCursor decoded = cursors.decodeTimeLong(cursor, scope);
        List<AdminReconciliationIssueResponse> fetched = repository.issues(
                status,
                severity,
                activityId,
                reservationNo,
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(),
                limit + 1
        );
        boolean more = fetched.size() > limit;
        List<AdminReconciliationIssueResponse> items = page(fetched, limit, more);
        AdminReconciliationIssueResponse last = more ? items.getLast() : null;
        String next = more ? cursors.encodeTimeLong(
                scope, LocalDateTime.ofInstant(last.lastSeenAt(), ZoneOffset.UTC), last.issueId()
        ) : null;
        return new CursorSlice<>(items, next, more);
    }

    public CursorSlice<AdminManualReviewResponse> manualReviews(int limit, String cursor) {
        String scope = scope("manual-reviews");
        AdminCursorCodec.TimeLongCursor decoded = cursors.decodeTimeLong(cursor, scope);
        List<AdminManualReviewResponse> fetched = repository.manualReviews(
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(),
                limit + 1
        );
        boolean more = fetched.size() > limit;
        List<AdminManualReviewResponse> items = page(fetched, limit, more);
        AdminManualReviewResponse last = more ? items.getLast() : null;
        String next = more ? cursors.encodeTimeLong(
                scope, LocalDateTime.ofInstant(last.updatedAt(), ZoneOffset.UTC), last.processingId()
        ) : null;
        return new CursorSlice<>(items, next, more);
    }

    public AdminReconciliationStatusResponse reconciliationStatus() {
        return repository.reconciliationStatus(reconciliationDryRun, reconciliationAutoRepair);
    }

    private Boolean optionalBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("Reconciliation mode must be true or false when supplied");
    }

    private <T> List<T> page(List<T> fetched, int limit, boolean more) {
        return more ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
    }

    private String scope(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            canonical.append('|').append(value == null ? "" : value);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
