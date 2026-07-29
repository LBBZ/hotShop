package com.real.common.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

public record FlashSaleActivityLoadResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long activityId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String result,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Integer databaseVersion,
        Integer redisVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Integer databaseAvailableStock,
        Integer redisAvailableStock,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long streamEventCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long reservationRecordCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Long reservedQuantity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean consistent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String detail
) {
}
