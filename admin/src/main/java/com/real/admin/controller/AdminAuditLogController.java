package com.real.admin.controller;

import com.real.admin.service.AdminAuditLogQueryService;
import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.audit.AuditAction;
import com.real.common.audit.AuditActorType;
import com.real.common.audit.AuditLogResponse;
import com.real.common.audit.AuditResourceType;
import com.real.common.audit.AuditResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Validated
@Tag(name = "Admin audit logs", description = "Read-only Administrator audit investigation")
@RequestMapping("/admin/api/v1/audit-logs")
@SecurityRequirement(name = "bearerAuth")
public class AdminAuditLogController {
    private final AdminAuditLogQueryService queryService;

    public AdminAuditLogController(AdminAuditLogQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(
            summary = "List audit logs",
            description = "Stable keyset pagination ordered by occurredAt descending, then auditId descending"
    )
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<CursorPageResponse<AuditLogResponse>> getAuditLogs(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurredTo,
            @RequestParam(required = false) AuditActorType actorType,
            @RequestParam(required = false) @Size(max = 128) String actorId,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditResourceType resourceType,
            @RequestParam(required = false) @Size(max = 128) String resourceId,
            @RequestParam(required = false) AuditResult result
    ) {
        if (occurredFrom != null && occurredTo != null && occurredFrom.isAfter(occurredTo)) {
            throw ApiException.badRequest(
                    "TIME_RANGE_INVALID",
                    "occurredFrom must be before or equal to occurredTo"
            );
        }
        CursorSlice<AuditLogResponse> slice = queryService.query(
                limit,
                cursor,
                occurredFrom,
                occurredTo,
                actorType,
                actorId,
                action,
                resourceType,
                resourceId,
                result
        );
        return ResponseEntity.ok(new CursorPageResponse<>(
                slice.items(),
                slice.nextCursor(),
                slice.hasMore()
        ));
    }
}
