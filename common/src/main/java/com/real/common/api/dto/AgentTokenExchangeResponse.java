package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

public record AgentTokenExchangeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Bearer")
        String tokenType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Set<String> scopes
) {
}
