package com.real.admin.controller;

import com.real.admin.service.AdminOperationsService;
import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.AdminManualReviewResponse;
import com.real.common.api.dto.AdminOperationsOverviewResponse;
import com.real.common.api.dto.AdminPaymentResponse;
import com.real.common.api.dto.AdminReconciliationIssueResponse;
import com.real.common.api.dto.AdminReconciliationStatusResponse;
import com.real.common.api.dto.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@Validated
@Tag(name = "Admin operations", description = "Bounded operational facts and incident investigation")
@RequestMapping("/admin/api/v1/operations")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminOperationsController {
    private final AdminOperationsService service;

    public AdminOperationsController(AdminOperationsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(summary = "Get bounded operations overview")
    public AdminOperationsOverviewResponse overview(
            @RequestParam(defaultValue = "24") @Min(1) @Max(168) int windowHours
    ) {
        return service.overview(windowHours);
    }

    @GetMapping("/payments")
    @Operation(summary = "List payment facts", description = "A bounded time range and signed stable cursor are required")
    public ResponseEntity<CursorPageResponse<AdminPaymentResponse>> payments(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String orderId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo
    ) {
        validateRange(createdFrom, createdTo, Duration.ofDays(31));
        CursorSlice<AdminPaymentResponse> slice = service.payments(
                limit, cursor, status == null ? null : status.name(), orderId, createdFrom, createdTo
        );
        return ResponseEntity.ok(new CursorPageResponse<>(slice.items(), slice.nextCursor(), slice.hasMore()));
    }

    @GetMapping("/reconciliation-issues")
    @Operation(summary = "List reconciliation findings", description = "Findings are not reported as repairs")
    public ResponseEntity<CursorPageResponse<AdminReconciliationIssueResponse>> issues(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "OPEN") IssueStatus status,
            @RequestParam(required = false) IssueSeverity severity,
            @RequestParam(required = false) @Min(1) Long activityId,
            @RequestParam(required = false)
            @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String reservationNo
    ) {
        CursorSlice<AdminReconciliationIssueResponse> slice = service.issues(
                limit, cursor, status.name(), severity == null ? null : severity.name(), activityId, reservationNo
        );
        return ResponseEntity.ok(new CursorPageResponse<>(slice.items(), slice.nextCursor(), slice.hasMore()));
    }

    @GetMapping("/manual-reviews")
    @Operation(summary = "List processing facts awaiting human review")
    public ResponseEntity<CursorPageResponse<AdminManualReviewResponse>> manualReviews(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor
    ) {
        CursorSlice<AdminManualReviewResponse> slice = service.manualReviews(limit, cursor);
        return ResponseEntity.ok(new CursorPageResponse<>(slice.items(), slice.nextCursor(), slice.hasMore()));
    }

    @GetMapping("/reconciliation-status")
    @Operation(summary = "Get reconciliation mode and persisted finding summary")
    public AdminReconciliationStatusResponse reconciliationStatus() {
        return service.reconciliationStatus();
    }

    private void validateRange(Instant from, Instant to, Duration maximum) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("TIME_RANGE_INVALID", "createdFrom must be before or equal to createdTo");
        }
        if (Duration.between(from, to).compareTo(maximum) > 0) {
            throw ApiException.badRequest("TIME_RANGE_TOO_LARGE", "Payment query range cannot exceed 31 days");
        }
    }

    public enum PaymentStatus { PENDING, SUCCEEDED, FAILED, CLOSED, LATE_SUCCEEDED }
    public enum IssueStatus { OPEN, RESOLVED, IGNORED }
    public enum IssueSeverity { INFO, WARNING, CRITICAL }
}
