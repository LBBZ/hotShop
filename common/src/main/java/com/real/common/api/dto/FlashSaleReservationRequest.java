package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record FlashSaleReservationRequest(
        @NotNull
        @Min(1)
        @Max(100000)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "100000")
        Integer quantity
) {
}
