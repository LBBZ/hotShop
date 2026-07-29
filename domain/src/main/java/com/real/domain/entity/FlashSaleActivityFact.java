package com.real.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FlashSaleActivityFact(
        long activityId,
        String activityCode,
        long productId,
        BigDecimal salePrice,
        int totalStock,
        int availableStock,
        int perUserLimit,
        String status,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        int version,
        Long catalogProductId,
        BigDecimal catalogPrice,
        Integer catalogStock,
        String catalogStatus,
        LocalDateTime catalogDeletedAt
) {
}
