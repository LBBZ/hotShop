package com.real.common.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminOperationReasonRequest(
        @NotBlank
        @Size(min = 3, max = 256)
        @Pattern(regexp = "^(?=(?:.*\\S){3,})[^\\r\\n]*$")
        String reason
) {
}
