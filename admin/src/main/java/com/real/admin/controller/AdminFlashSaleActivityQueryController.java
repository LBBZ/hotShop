package com.real.admin.controller;

import com.real.admin.service.AdminOperationsService;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.AdminFlashSaleActivityResponse;
import com.real.common.api.dto.CursorPageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Admin flash-sale activity queries", description = "Administrator activity facts")
@RequestMapping("/admin/api/v1/flash-sales")
@SecurityRequirement(name = "bearerAuth")
public class AdminFlashSaleActivityQueryController {
    private final AdminOperationsService operations;

    public AdminFlashSaleActivityQueryController(AdminOperationsService operations) {
        this.operations = operations;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "List Flash Sale Activities", description = "Signed stable cursor ordered by activityId descending")
    public ResponseEntity<CursorPageResponse<AdminFlashSaleActivityResponse>> activities(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) ActivityStatus status,
            @RequestParam(required = false) @Min(1) Long productId
    ) {
        CursorSlice<AdminFlashSaleActivityResponse> slice = operations.activities(
                limit, cursor, status == null ? null : status.name(), productId
        );
        return ResponseEntity.ok(new CursorPageResponse<>(slice.items(), slice.nextCursor(), slice.hasMore()));
    }

    public enum ActivityStatus { DRAFT, SCHEDULED, ACTIVE, PAUSED, ENDED, CANCELED }
}
