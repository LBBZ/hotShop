package com.real.admin.agenttools;

public record AdminAgentAnomalyCount(
        String code,
        String severity,
        long count
) {
}
