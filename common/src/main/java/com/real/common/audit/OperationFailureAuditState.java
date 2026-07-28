package com.real.common.audit;

public record OperationFailureAuditState(String reasonCode) implements AuditStateSummary {
    public OperationFailureAuditState {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("Failure reason code is required");
        }
    }
}
