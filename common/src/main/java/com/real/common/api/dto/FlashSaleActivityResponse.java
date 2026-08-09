package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "A current or upcoming Flash Sale Activity with server-clock calibration")
public record FlashSaleActivityResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$",
                requiredMode = Schema.RequiredMode.REQUIRED) Long activityId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String activityCode,
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$",
                requiredMode = Schema.RequiredMode.REQUIRED) Long productId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String productName,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String category,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$",
                requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal salePrice,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer availableStock,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer perUserLimit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(allowableValues = {"UPCOMING", "LIVE", "SOLD_OUT", "EXPIRED"},
                requiredMode = Schema.RequiredMode.REQUIRED) String phase,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant endsAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant serverTime
) { }
