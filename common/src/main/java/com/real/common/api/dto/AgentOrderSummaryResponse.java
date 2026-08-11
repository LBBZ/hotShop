package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

public record AgentOrderSummaryResponse(
        String orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
        String currency,
        String status,
        Instant createdAt,
        int itemCount
) { }
