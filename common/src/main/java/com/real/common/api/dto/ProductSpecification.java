package com.real.common.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductSpecification(
        @NotBlank @Size(max = 40) String name,
        @NotBlank @Size(max = 160) String value
) { }
