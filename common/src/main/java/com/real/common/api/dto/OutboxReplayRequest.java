package com.real.common.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OutboxReplayRequest(
        @NotBlank @Size(min = 3, max = 256) String reason
) { }
