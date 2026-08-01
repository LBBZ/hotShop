package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record OutboxFailedEventResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String eventType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String aggregateType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String aggregateId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int publishAttempts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int consecutiveAttempts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        int manualReplayCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String failureCategory,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant failedAt
) { }
