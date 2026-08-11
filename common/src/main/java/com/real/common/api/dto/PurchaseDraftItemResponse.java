package com.real.common.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

public record PurchaseDraftItemResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long productId,
        String productName,
        int quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPriceSnapshot,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lineAmountSnapshot,
        String currency
) { }
