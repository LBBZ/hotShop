package com.real.common.api.dto;

import com.real.common.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record OrderCreatedResponse(
        @Schema(type = "string", pattern = "^[A-Za-z0-9_-]{1,64}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String orderId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status,
        @Schema(description = "Correlation ID retained by the durable purchase intent",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId,
        @Schema(description = "True when this response replays a persisted Idempotency-Key result",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean idempotencyReplayed
) {
}
