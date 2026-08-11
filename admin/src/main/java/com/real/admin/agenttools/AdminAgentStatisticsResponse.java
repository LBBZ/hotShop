package com.real.admin.agenttools;

import java.time.Instant;

public record AdminAgentStatisticsResponse(
        Instant generatedAt,
        long activeProductCount,
        long availableProductUnits,
        long ordersCreatedLast24Hours,
        long paidOrdersLast24Hours,
        long activeReservationCount
) {
}
