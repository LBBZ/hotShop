package com.real.common.audit;

public sealed interface AuditStateSummary permits
        AuthenticationAuditState,
        RefreshReuseAuditState,
        AgentDelegationAuditState,
        ProductMutationAuditState,
        FlashSaleActivityLoadAuditState,
        OutboxReplayAuditState,
        OperationFailureAuditState {
}
