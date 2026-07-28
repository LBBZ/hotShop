package com.real.common.audit;

public record AgentDelegationAuditState(int scopeCount) implements AuditStateSummary {
    public AgentDelegationAuditState {
        if (scopeCount < 0) {
            throw new IllegalArgumentException("Scope count cannot be negative");
        }
    }
}
