package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AgentProductComparisonRequest(
        @NotEmpty
        @Size(min = 2, max = 10)
        List<@Pattern(regexp = "^[1-9][0-9]{0,18}$") String> productIds
) {
    public AgentProductComparisonRequest {
        productIds = productIds == null ? null : List.copyOf(productIds);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unexpected Agent tool request field");
    }
}
