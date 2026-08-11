package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentProductSummaryResponse(
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$")
        Long productId,
        String name,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal price,
        String currency,
        String category,
        String description,
        boolean available
) { }
