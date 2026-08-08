package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "A local demonstration Mock Payment; it is not a real payment")
public record PaymentResponse(
        String paymentNo,
        String orderId,
        @Schema(example = "MOCK") String provider,
        BigDecimal amount,
        String currency,
        String status,
        Instant expiresAt,
        Instant paidAt,
        @Schema(example = "true") boolean localDemoOnly,
        @Schema(example = "Mock Payment only; no real funds are transferred") String notice
) { }
