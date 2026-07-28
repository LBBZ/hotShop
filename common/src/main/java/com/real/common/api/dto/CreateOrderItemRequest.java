package com.real.common.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.real.common.api.json.LongIdStringDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderItemRequest(
        @NotNull
        @Positive
        @JsonDeserialize(using = LongIdStringDeserializer.class)
        @Schema(type = "string", pattern = "^[1-9][0-9]*$", example = "456")
        Long productId,
        @NotNull
        @Positive
        Integer quantity
) {
}
