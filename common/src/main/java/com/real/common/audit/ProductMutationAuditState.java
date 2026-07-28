package com.real.common.audit;

import java.util.List;

public record ProductMutationAuditState(
        List<String> changedFields,
        String lifecycleStatus
) implements AuditStateSummary {
    public ProductMutationAuditState {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
