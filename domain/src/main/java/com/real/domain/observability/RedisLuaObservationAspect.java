package com.real.domain.observability;

import com.real.common.observability.AsyncTraceContext;
import com.real.domain.service.seckill.FlashSaleReservationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RedisLuaObservationAspect {
    private static final Logger log = LoggerFactory.getLogger(RedisLuaObservationAspect.class);
    private final MeterRegistry meters;
    private final Tracer tracer;

    public RedisLuaObservationAspect(MeterRegistry meters, Tracer tracer) {
        this.meters = meters;
        this.tracer = tracer;
    }

    @Around("execution(* com.real.domain.service.seckill.FlashSaleReservationService.reserve(..))")
    public Object observeReservation(ProceedingJoinPoint invocation) throws Throwable {
        Timer.Sample sample = null;
        String outcome = "failure";
        Span span = null;
        Tracer.SpanInScope scope = null;
        try {
            sample = Timer.start(meters);
        } catch (RuntimeException observationFailure) {
            observationFailure("timer_start", observationFailure);
        }
        try {
            span = tracer.spanBuilder().name("redis.lua.reservation")
                    .kind(Span.Kind.CLIENT).remoteServiceName("redis-seckill").start();
            scope = tracer.withSpan(span);
        } catch (RuntimeException observationFailure) {
            observationFailure("span_start", observationFailure);
        }
        try {
            Object returned = invocation.proceed();
            if (returned instanceof FlashSaleReservationResult result) {
                outcome = result.code().name().toLowerCase();
            }
            return returned;
        } catch (Throwable failure) {
            outcome = "exception";
            try {
                if (span != null) span.error(failure);
            } catch (RuntimeException observationFailure) {
                observationFailure("span_error", observationFailure);
            }
            throw failure;
        } finally {
            closeScope(scope);
            finishSpan(span, outcome);
            recordMetrics(sample, outcome);
        }
    }

    private void closeScope(Tracer.SpanInScope scope) {
        try {
            if (scope != null) scope.close();
        } catch (RuntimeException observationFailure) {
            observationFailure("scope_close", observationFailure);
        }
    }

    private void finishSpan(Span span, String outcome) {
        try {
            if (span != null) {
                span.tag("db.operation", "EVALSHA").tag("outcome", outcome).end();
            }
        } catch (RuntimeException observationFailure) {
            observationFailure("span_finish", observationFailure);
        }
    }

    private void recordMetrics(Timer.Sample sample, String outcome) {
        try {
            meters.counter("hotshop.redis.lua.calls", "script", "reservation-v1",
                    "outcome", outcome).increment();
            if (sample != null) {
                sample.stop(Timer.builder("hotshop.redis.lua.duration")
                        .description("Redis Lua execution duration")
                        .tag("script", "reservation-v1")
                        .tag("outcome", outcome)
                        .publishPercentileHistogram()
                        .register(meters));
            }
        } catch (RuntimeException observationFailure) {
            observationFailure("metric_record", observationFailure);
        }
    }

    private void observationFailure(String stage, RuntimeException failure) {
        try {
            meters.counter("hotshop.observability.failures", "component", "redis-lua",
                    "stage", stage).increment();
        } catch (RuntimeException ignored) {
            // The observation failure path must remain independent of business execution.
        }
        log.warn("Redis Lua observation degraded stage={} failureType={}",
                stage, failure.getClass().getSimpleName());
    }
}
