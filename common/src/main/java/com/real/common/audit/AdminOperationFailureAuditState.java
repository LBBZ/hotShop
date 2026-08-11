package com.real.common.audit;

public record AdminOperationFailureAuditState(
        String reasonCode,
        String administratorReason
) implements AuditStateSummary {
    public AdminOperationFailureAuditState {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("Failure reason code is required");
        }
        if (administratorReason == null || administratorReason.isBlank()
                || administratorReason.length() > 256) {
            throw new IllegalArgumentException("Administrator reason must contain 1 to 256 characters");
        }
        administratorReason = administratorReason.strip();
    }
}
