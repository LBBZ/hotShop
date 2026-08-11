package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PurchaseDraftItemRequest(
        @NotNull
        @Pattern(regexp = "^[1-9][0-9]{0,18}$")
        String productId,
        @NotNull
        @Max(100)
        @JsonDeserialize(using = AgentStrictPositiveIntegerDeserializer.class)
        Integer quantity
) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unexpected Purchase item request field");
    }
}
