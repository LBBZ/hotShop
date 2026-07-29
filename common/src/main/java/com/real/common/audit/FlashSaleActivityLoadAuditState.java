package com.real.common.audit;

public record FlashSaleActivityLoadAuditState(
        String loadResult,
        int databaseVersion,
        Integer redisVersion,
        int databaseAvailableStock,
        Integer redisAvailableStock,
        long streamEventCount,
        long reservationRecordCount,
        boolean consistent
) implements AuditStateSummary {
}
