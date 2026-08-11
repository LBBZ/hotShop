package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PurchaseDraftResponse(
        String draftId,
        String actionType,
        List<PurchaseDraftItemResponse> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalPriceSnapshot,
        String currency,
        Instant validUntil,
        boolean confirmationRequired,
        String nextStep
) {
    public PurchaseDraftResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
