package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminPaymentResponse(
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long paymentId,
        String paymentNo,
        String orderId,
        String provider,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String status,
        Instant expiresAt,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
}
