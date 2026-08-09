package com.real.common.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Durable, monotonically ordered User transaction event")
public record TransactionTimelineEventResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$",
                requiredMode = Schema.RequiredMode.REQUIRED) Long eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resourceType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resourceId,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String reservationNo,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String orderId,
        @Schema(allowableValues = {
                "RESERVED", "ORDER_CREATED", "PENDING_PAYMENT", "PAYMENT_FAILED",
                "PAID", "CLOSED", "CANCELED", "COMPENSATING", "COMPENSATED",
                "LATE_SUCCEEDED"
        }, requiredMode = Schema.RequiredMode.REQUIRED) String eventType,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detailCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt
) { }
