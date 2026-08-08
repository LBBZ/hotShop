package com.real.portal.payment;

import com.real.common.api.RequestContext;
import com.real.common.audit.*;
import com.real.security.audit.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PaymentCallbackAuditService {
    private final AuditLogWriter writer;
    public PaymentCallbackAuditService(AuditLogWriter writer) { this.writer = writer; }

    public void accepted(String paymentNo, String outcome, String result,
            String previous, String next, HttpServletRequest request) {
        writer.append(event(AuditAction.MOCK_PAYMENT_CALLBACK_ACCEPTED, AuditResult.SUCCESS,
                paymentNo, outcome, result, null, previous, next, request));
    }

    public void rejected(String paymentNo, String outcome, String category, HttpServletRequest request) {
        writer.appendFailure(event(AuditAction.MOCK_PAYMENT_CALLBACK_REJECTED, AuditResult.DENIED,
                paymentNo, outcome, "REJECTED", category, null, null, request));
    }

    private AuditEvent event(AuditAction action, AuditResult result, String paymentNo, String outcome,
            String businessResult, String category, String previous, String next,
            HttpServletRequest request) {
        String safePayment = paymentNo != null && paymentNo.matches("MOCK_[0-9a-f]{32}") ? paymentNo : null;
        return new AuditEvent(AuditActor.system(), null, action,
                new AuditResource(AuditResourceType.PAYMENT_CALLBACK, safePayment), result,
                RequestContext.requestId(request), RequestContext.traceId(request), AuditSource.MOCK_PROVIDER,
                Instant.now(), new PaymentCallbackAuditState("MOCK", safePayment, outcome,
                        businessResult, category, previous, next));
    }
}
