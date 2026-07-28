package com.real.common.audit;

import java.time.Instant;
import java.util.Objects;

public record AuditEvent(
        AuditActor actor,
        AuditActor delegatedActor,
        AuditAction action,
        AuditResource resource,
        AuditResult result,
        String requestId,
        String traceId,
        AuditSource source,
        Instant occurredAt,
        AuditStateSummary stateSummary
) {
    public AuditEvent {
        Objects.requireNonNull(actor, "Audit actor is required");
        Objects.requireNonNull(action, "Audit action is required");
        Objects.requireNonNull(resource, "Audit resource is required");
        Objects.requireNonNull(result, "Audit result is required");
        Objects.requireNonNull(source, "Audit source is required");
        Objects.requireNonNull(occurredAt, "Audit occurrence time is required");
        Objects.requireNonNull(stateSummary, "Audit state summary is required");
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new IllegalArgumentException("Audit request ID is required");
        }
        if (traceId == null || !traceId.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("Audit trace ID must be a 32-character lowercase hex value");
        }
    }
}
