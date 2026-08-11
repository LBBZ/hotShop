package com.real.admin.agenttools;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record AgentConfigurationDraftResponse(
        String configurationDraftId,
        String configurationKey,
        JsonNode proposedValue,
        String riskLevel,
        String status,
        Instant createdAt
) {
    public AgentConfigurationDraftResponse {
        proposedValue = proposedValue.deepCopy();
    }
}
