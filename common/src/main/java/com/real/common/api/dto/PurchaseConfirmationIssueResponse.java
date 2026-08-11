package com.real.common.api.dto;

import java.time.Instant;

public record PurchaseConfirmationIssueResponse(
        String draftId,
        String actionType,
        String confirmationToken,
        Instant expiresAt
) { }
