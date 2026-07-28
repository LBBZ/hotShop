package com.real.common.api.dto;

import com.real.common.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record OrderCreatedResponse(
        @Schema(type = "string", pattern = "^[A-Za-z0-9_-]{1,64}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String orderId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status
) {
}
