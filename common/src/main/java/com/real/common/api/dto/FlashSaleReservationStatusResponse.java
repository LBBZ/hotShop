package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Current state of the authenticated User's flash-sale Reservation")
public record FlashSaleReservationStatusResponse(
        @Schema(
                example = "rsv_0123456789abcdef0123456789abcdef",
                pattern = "^rsv_[0-9a-f]{32}$",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String reservationNo,
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(
                type = "string",
                example = "7001",
                pattern = "^[1-9][0-9]{0,18}$",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Long activityId,
        @Schema(
                example = "ORDER_CREATED",
                allowableValues = {
                        "RESERVED",
                        "ORDER_CREATED",
                        "COMPENSATING",
                        "COMPENSATED",
                        "EXPIRED",
                        "CANCELED"
                },
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String status,
        @Schema(
                example = "ord_0123456789abcdef0123456789abcdef",
                pattern = "^ord_[0-9a-f]{32}$",
                nullable = true
        )
        String orderId,
        @Schema(example = "1", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(
                type = "string",
                example = "19.90",
                pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        BigDecimal reservedAmount,
        @Schema(
                example = "CNY",
                allowableValues = "CNY",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String currency
) {
}
