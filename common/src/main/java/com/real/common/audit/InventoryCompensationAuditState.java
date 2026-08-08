package com.real.common.audit;

public record InventoryCompensationAuditState(
        String orderType,
        String reason,
        String orderId,
        String reservationNo,
        int restoredRows
) implements AuditStateSummary { }
