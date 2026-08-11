package com.real.common.api.dto;

import java.time.Instant;

public record AdminOperationsOverviewResponse(
        Instant rangeFrom,
        Instant rangeTo,
        Instant generatedAt,
        String source,
        long productsCreated,
        long activitiesCreated,
        long ordersCreated,
        long reservationsCreated,
        long paymentsCreated,
        long failedOutboxUpdated,
        long openReconciliationIssues,
        long pendingManualReviews
) {
}
