package com.real.common.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;

public record MockPaymentActionRequest(
        @NotNull @Pattern(regexp = "SUCCEEDED|FAILED") String outcome,
        @NotNull Duration delay,
        @Min(1) @Max(10) int duplicateCount
) { }
