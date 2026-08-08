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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Aspect
@Component
public class RedisLuaObservationAspect {
    private final MeterRegistry meters;
    private final StringRedisTemplate redis;
    private final Tracer tracer;

    public RedisLuaObservationAspect(
            MeterRegistry meters,
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis,
            Tracer tracer
    ) {
        this.meters = meters;
        this.redis = redis;
        this.tracer = tracer;
    }

    @Around("execution(* com.real.domain.service.seckill.FlashSaleReservationService.reserve(..))")
    public Object observeReservation(ProceedingJoinPoint invocation) throws Throwable {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "failure";
        Span span = tracer.spanBuilder().name("redis.lua.reservation")
                .kind(Span.Kind.CLIENT).remoteServiceName("redis-seckill").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Object returned = invocation.proceed();
            if (returned instanceof FlashSaleReservationResult result) {
                outcome = result.code().name().toLowerCase();
                Object[] arguments = invocation.getArgs();
                String requestId = arguments.length >= 5 ? String.valueOf(arguments[4]) : "";
                String traceParent = AsyncTraceContext.currentTraceParent();
                if (!requestId.isBlank() && !traceParent.isBlank()) {
                    redis.opsForValue().set(
                            AsyncTraceContext.redisKey(requestId),
                            traceParent,
                            Duration.ofDays(7)
                    );
                }
            }
            return returned;
        } catch (Throwable failure) {
            outcome = "exception";
            span.error(failure);
            throw failure;
        } finally {
            span.tag("db.operation", "EVALSHA").tag("outcome", outcome).end();
            meters.counter("hotshop.redis.lua.calls", "script", "reservation-v1",
                    "outcome", outcome).increment();
            sample.stop(Timer.builder("hotshop.redis.lua.duration")
                    .description("Redis Lua execution duration")
                    .tag("script", "reservation-v1")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters));
        }
    }
}
