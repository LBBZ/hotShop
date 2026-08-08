package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record MockPaymentActionResponse(
        String callbackId,
        @Schema(example = "MOCK") String provider,
        String paymentNo,
        String outcome,
        Instant deliveryAvailableAt,
        int duplicateCount,
        @Schema(example = "true") boolean localDemoOnly,
        @Schema(example = "Mock Payment only; no real funds are transferred") String notice
) { }
