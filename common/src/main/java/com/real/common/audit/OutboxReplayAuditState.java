package com.real.common.audit;

public record OutboxReplayAuditState(String reason, String outcome) implements AuditStateSummary {
    public OutboxReplayAuditState {
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("Replay reason must contain 1 to 256 characters");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("Replay outcome is required");
        }
    }
}
