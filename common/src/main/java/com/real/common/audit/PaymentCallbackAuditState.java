package com.real.common.audit;

public record PaymentCallbackAuditState(
        String provider,
        String paymentNo,
        String outcome,
        String result,
        String category,
        String previousStatus,
        String newStatus
) implements AuditStateSummary { }
