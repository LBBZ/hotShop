package com.real.portal.observability;

import com.real.common.api.dto.MockPaymentCallbackResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PaymentObservabilityAspect {
    private final MeterRegistry meters;

    public PaymentObservabilityAspect(MeterRegistry meters) {
        this.meters = meters;
    }

    @Around("execution(* com.real.portal.payment.MockPaymentCallbackService.accept(..))")
    public Object observeCallback(ProceedingJoinPoint invocation) throws Throwable {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "failure";
        String result = "rejected";
        try {
            Object returned = invocation.proceed();
            outcome = "success";
            if (returned instanceof MockPaymentCallbackResponse response) {
                result = response.result().toLowerCase();
            }
            return returned;
        } finally {
            meters.counter("hotshop.payment.callbacks", "outcome", outcome,
                    "result", normalize(result)).increment();
            sample.stop(Timer.builder("hotshop.payment.callback.duration")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters));
        }
    }

    private static String normalize(String value) {
        String safe = value == null ? "unknown" : value.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return safe.matches("(payment_)?(succeeded|failed|late_succeeded)|idempotent|rejected")
                ? safe : "other";
    }
}
