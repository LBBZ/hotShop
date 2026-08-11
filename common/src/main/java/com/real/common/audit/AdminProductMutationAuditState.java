package com.real.common.audit;

import java.util.List;

public record AdminProductMutationAuditState(
        List<String> changedFields,
        String lifecycleStatus,
        String reason
) implements AuditStateSummary {
    public AdminProductMutationAuditState {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("Administrator reason must contain 1 to 256 characters");
        }
        reason = reason.strip();
    }
}
