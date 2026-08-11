package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PurchaseConfirmationConsumeRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$")
        String confirmationToken,
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        String draftId,
        @NotBlank
        @Size(max = 32)
        String actionType,
        @NotEmpty
        @Size(max = 20)
        List<@Valid PurchaseDraftItemRequest> items
) {
    public PurchaseConfirmationConsumeRequest {
        items = items == null ? null : List.copyOf(items);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unexpected Purchase Confirmation request field");
    }
}
