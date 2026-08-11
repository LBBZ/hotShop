package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;

public record AgentReservationSummaryResponse(
        String reservationNo,
        @JsonSerialize(using = ToStringSerializer.class) Long activityId,
        @JsonSerialize(using = ToStringSerializer.class) Long productId,
        int quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reservedAmount,
        String currency,
        String status,
        String orderId,
        Instant createdAt
) { }
