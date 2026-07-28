package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record OrderItemResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long orderItemId,
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long productId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal unitPrice,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal lineAmount
) {
}
