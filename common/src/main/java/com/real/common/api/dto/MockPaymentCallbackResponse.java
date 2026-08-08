package com.real.common.api.dto;

public record MockPaymentCallbackResponse(
        String callbackId,
        String result,
        boolean acknowledged
) { }
