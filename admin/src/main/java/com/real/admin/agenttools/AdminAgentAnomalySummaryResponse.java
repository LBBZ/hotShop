package com.real.admin.agenttools;

import java.time.Instant;
import java.util.List;

public record AdminAgentAnomalySummaryResponse(
        Instant generatedAt,
        List<AdminAgentAnomalyCount> anomalies
) {
    public AdminAgentAnomalySummaryResponse {
        anomalies = List.copyOf(anomalies);
    }
}
