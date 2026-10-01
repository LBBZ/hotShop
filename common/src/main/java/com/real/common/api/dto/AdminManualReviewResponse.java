package com.real.common.api.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record AdminManualReviewResponse(
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long processingId,
        String eventId,
        String reservationNo,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long activityId,
        String status,
        int attempts,
        String reasonCode,
        String lastError,
        Instant updatedAt
) {
}
