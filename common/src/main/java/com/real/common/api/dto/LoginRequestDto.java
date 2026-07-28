package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequestDto(
        @NotBlank
        @Size(max = 64)
        String username,
        @NotBlank
        @Size(max = 128)
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        String password
) {
}
