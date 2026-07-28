package com.real.common.audit;

public record RefreshReuseAuditState(
        String reason,
        boolean familyRevoked
) implements AuditStateSummary {
}
