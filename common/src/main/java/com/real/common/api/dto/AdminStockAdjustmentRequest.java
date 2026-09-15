package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record AdminStockAdjustmentRequest(
        @NotNull
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
                using = com.real.common.api.json.StrictSignedIntegerDeserializer.class)
        Integer delta,
        @NotBlank @Pattern(regexp = "^(0|[1-9][0-9]{0,9})$")
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]{0,9})$", example = "0")
        String expectedVersion,
        @NotBlank @Size(min = 3, max = 256) @Pattern(regexp = "^(?=(?:.*\\S){3,})[^\\r\\n]*$") String reason
) {}
