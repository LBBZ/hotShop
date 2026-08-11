package com.real.admin.controller;

import com.real.admin.service.AdminFlashSaleActivityLoadService;
import com.real.common.api.dto.FlashSaleActivityLoadResponse;
import com.real.common.api.dto.AdminOperationReasonRequest;
import com.real.domain.service.seckill.FlashSaleLoadResult;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Admin flash-sale activities", description = "Audited Redis activity loading and verification")
@RequestMapping("/admin/api/v1/flash-sales")
@SecurityRequirement(name = "bearerAuth")
public class AdminFlashSaleActivityController {
    private final AdminFlashSaleActivityLoadService loadService;

    public AdminFlashSaleActivityController(AdminFlashSaleActivityLoadService loadService) {
        this.loadService = loadService;
    }

    @Operation(
            summary = "Load a Flash Sale Activity into redis-seckill",
            description = "Loads validated MySQL facts by version and returns Redis/MySQL reconciliation."
    )
    @PostMapping("/{activityId}/load")
    @PreAuthorize("hasAuthority('PERM_ADMIN_FLASH_SALE_LOAD')")
    public ResponseEntity<FlashSaleActivityLoadResponse> load(
            @PathVariable @Min(1) Long activityId,
            @RequestBody @Valid AdminOperationReasonRequest operation,
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest request
    ) {
        FlashSaleLoadResult result = loadService.load(
                activityId,
                administrator.getUserId(),
                operation.reason(),
                request
        );
        return ResponseEntity.ok(new FlashSaleActivityLoadResponse(
                result.activityId(),
                result.code().name(),
                result.databaseVersion(),
                result.redisVersion(),
                result.databaseAvailableStock(),
                result.redisAvailableStock(),
                result.streamEventCount(),
                result.reservationRecordCount(),
                result.reservedQuantity(),
                result.consistent(),
                result.detail()
        ));
    }
}
