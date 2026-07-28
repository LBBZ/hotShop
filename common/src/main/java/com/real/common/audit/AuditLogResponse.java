package com.real.common.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$", example = "123")
        long auditId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AuditActorType actorType,
        @Schema(nullable = true)
        String actorId,
        @Schema(nullable = true)
        AuditActorType delegatedActorType,
        @Schema(nullable = true)
        String delegatedActorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AuditAction action,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AuditResourceType resourceType,
        @Schema(nullable = true)
        String resourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AuditResult result,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{32}$")
        String traceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AuditSource source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant occurredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, Object> stateSummary
) {
    public AuditLogResponse {
        stateSummary = stateSummary == null ? Map.of() : Map.copyOf(stateSummary);
    }
}
