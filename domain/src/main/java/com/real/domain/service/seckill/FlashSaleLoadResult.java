package com.real.domain.service.seckill;

public record FlashSaleLoadResult(
        FlashSaleLoadCode code,
        long activityId,
        int databaseVersion,
        Integer redisVersion,
        int databaseAvailableStock,
        Integer redisAvailableStock,
        long streamEventCount,
        long reservationRecordCount,
        long reservedQuantity,
        boolean consistent,
        String detail
) {
}
