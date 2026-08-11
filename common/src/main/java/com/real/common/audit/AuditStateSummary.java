package com.real.common.audit;

public sealed interface AuditStateSummary permits
        AuthenticationAuditState,
        RefreshReuseAuditState,
        AgentDelegationAuditState,
        ProductMutationAuditState,
        FlashSaleActivityLoadAuditState,
        OutboxReplayAuditState,
        OperationFailureAuditState,
        InventoryCompensationAuditState,
        PaymentCallbackAuditState,
        AdminProductMutationAuditState,
        AdminFlashSaleActivityLoadAuditState,
        AdminOperationFailureAuditState {
    // Administrator operation summaries are separate DTOs so reasons can be audited
    // without broadening legacy summaries used by other identity domains.
}
