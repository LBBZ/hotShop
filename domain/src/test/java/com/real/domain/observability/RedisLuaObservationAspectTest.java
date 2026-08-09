package com.real.domain.observability;

import com.real.domain.service.seckill.FlashSaleReservationCode;
import com.real.domain.service.seckill.FlashSaleReservationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisLuaObservationAspectTest {
    @Test
    void tracerFailureDoesNotReplaceAcceptedOrReplayBusinessResults() throws Throwable {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder()).thenThrow(new IllegalStateException("telemetry unavailable"));
        RedisLuaObservationAspect aspect = new RedisLuaObservationAspect(
                mock(MeterRegistry.class), tracer
        );
        FlashSaleReservationResult accepted = result(FlashSaleReservationCode.ACCEPTED);
        FlashSaleReservationResult replay = result(FlashSaleReservationCode.IDEMPOTENT_REPLAY);
        ProceedingJoinPoint acceptedCall = mock(ProceedingJoinPoint.class);
        ProceedingJoinPoint replayCall = mock(ProceedingJoinPoint.class);
        when(acceptedCall.proceed()).thenReturn(accepted);
        when(replayCall.proceed()).thenReturn(replay);

        assertThat(aspect.observeReservation(acceptedCall)).isSameAs(accepted);
        assertThat(aspect.observeReservation(replayCall)).isSameAs(replay);
    }

    @Test
    void tracerFailureDoesNotReplaceOriginalBusinessException() throws Throwable {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder()).thenThrow(new IllegalStateException("telemetry unavailable"));
        RedisLuaObservationAspect aspect = new RedisLuaObservationAspect(
                mock(MeterRegistry.class), tracer
        );
        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        IllegalArgumentException businessFailure = new IllegalArgumentException("business result");
        when(invocation.proceed()).thenThrow(businessFailure);

        assertThatThrownBy(() -> aspect.observeReservation(invocation)).isSameAs(businessFailure);
    }

    private static FlashSaleReservationResult result(FlashSaleReservationCode code) {
        return new FlashSaleReservationResult(
                code,
                "rsv_0123456789abcdef0123456789abcdef",
                1,
                "RESERVED",
                "request-1",
                "1-0"
        );
    }
}
