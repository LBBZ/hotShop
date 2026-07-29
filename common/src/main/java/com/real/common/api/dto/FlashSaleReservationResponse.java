package com.real.common.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

public record FlashSaleReservationResponse(
        @Schema(pattern = "^rsv_[0-9a-f]{32}$", requiredMode = Schema.RequiredMode.REQUIRED)
        String reservationNo,
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long activityId,
        @Schema(allowableValues = "RESERVED", requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(pattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId
) {
}
