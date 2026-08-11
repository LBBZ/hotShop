package com.real.common.api.dto;

import java.time.Instant;

public record PurchaseConfirmationOrderResponse(
        String orderId,
        String status,
        String draftId,
        Instant confirmedAt
) { }
