package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminFlashSaleActivityResponse(
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long activityId,
        String activityCode,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long productId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        int totalStock,
        int availableStock,
        int perUserLimit,
        String status,
        Instant startsAt,
        Instant endsAt,
        int version,
        Instant updatedAt
) {
}
