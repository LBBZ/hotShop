package com.real.admin.controller;

import com.real.admin.service.AdminOutboxService;
import com.real.common.api.ApiException;
import com.real.common.api.dto.OutboxFailedPageResponse;
import com.real.common.api.dto.OutboxReplayRequest;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@Tag(name = "Admin outbox operations", description = "Administrator-only failed event investigation and replay")
@RequestMapping("/admin/api/v1/outbox")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class AdminOutboxController {
    private static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";
    private final AdminOutboxService service;

    public AdminOutboxController(AdminOutboxService service) { this.service = service; }

    @GetMapping("/failed")
    @Operation(summary = "List failed Outbox events", description = "Stable keyset pagination; payload and raw errors are never returned")
    public OutboxFailedPageResponse failed(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        return service.failed(limit, cursor);
    }

    @PostMapping("/{eventId}/replay")
    @Operation(summary = "Replay one failed Outbox event", description = "Changes MySQL state only; asynchronous publication is performed by task")
    @ApiResponse(responseCode = "202", description = "Replay accepted for asynchronous publication")
    public ResponseEntity<Void> replay(
            @PathVariable @Pattern(regexp = UUID) String eventId,
            @RequestBody @Valid OutboxReplayRequest request,
            @AuthenticationPrincipal CustomUserDetails principal,
            HttpServletRequest httpRequest) {
        AdminOutboxService.ReplayResult result = service.replay(
                eventId, request.reason(), principal.getUserId(), httpRequest);
        if (result == AdminOutboxService.ReplayResult.NOT_FOUND) {
            throw ApiException.notFound("Outbox event");
        }
        if (result == AdminOutboxService.ReplayResult.NOT_FAILED) {
            throw ApiException.conflict("OUTBOX_NOT_FAILED", "Only FAILED Outbox events can be replayed");
        }
        return ResponseEntity.accepted().build();
    }
}
