package com.real.common.api.dto;

import java.time.Instant;

public record AdminReconciliationStatusResponse(
        Boolean dryRun,
        Boolean autoRepair,
        Instant lastCheckpointAt,
        long openIssues,
        long criticalOpenIssues,
        String factStatement
) {
}
