package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$", example = "456",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long productId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = "string", pattern = "^(0|[1-9][0-9]*)\\.[0-9]{2}$", example = "6999.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal price,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Integer stock,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String category,
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt
) {
}
