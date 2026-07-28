package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.real.common.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        @Schema(type = "string", pattern = "^[A-Za-z0-9_-]{1,64}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String orderId,
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal totalAmount,
        @Schema(type = "string", pattern = "^[A-Z]{3}$", example = "CNY",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<OrderItemResponse> items
) {
    public OrderResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
