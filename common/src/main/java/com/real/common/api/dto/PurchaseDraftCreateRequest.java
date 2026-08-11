package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PurchaseDraftCreateRequest(
        @NotEmpty
        @Size(max = 20)
        List<@Valid PurchaseDraftItemRequest> items
) {
    public PurchaseDraftCreateRequest {
        items = items == null ? null : List.copyOf(items);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unexpected Purchase Draft request field");
    }
}
