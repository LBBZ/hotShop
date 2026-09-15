package com.real.common.audit;

public record StockAdjustmentAuditState(int delta, int stockBefore, int stockAfter,
        long versionBefore, long versionAfter, String reason) implements AuditStateSummary {}
