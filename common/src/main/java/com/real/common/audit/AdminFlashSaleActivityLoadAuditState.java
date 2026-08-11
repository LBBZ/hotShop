package com.real.common.audit;

public record AdminFlashSaleActivityLoadAuditState(
        String loadResult,
        int databaseVersion,
        Integer redisVersion,
        int databaseAvailableStock,
        Integer redisAvailableStock,
        long streamEventCount,
        long reservationRecordCount,
        boolean consistent,
        String reason
) implements AuditStateSummary {
    public AdminFlashSaleActivityLoadAuditState {
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("Administrator reason must contain 1 to 256 characters");
        }
        reason = reason.strip();
    }
}
