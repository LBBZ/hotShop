package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MessageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String message
) {
}
