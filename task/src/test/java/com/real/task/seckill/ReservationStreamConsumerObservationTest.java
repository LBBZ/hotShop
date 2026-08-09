package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReservationStreamConsumerObservationTest {
    @Test
    @SuppressWarnings("unchecked")
    void emptyCarrierClearsAmbientTraceWhenTracerAndTimerFailAndRestoresMdc() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.acknowledge(any(String.class), anyString(), any(String[].class)))
                .thenReturn(1L);
        SeckillRedisReservationGateway gateway = mock(SeckillRedisReservationGateway.class);
        when(gateway.verify(any())).thenReturn(
                new SeckillRedisReservationGateway.ReservationProof(true, "RESERVED", null));
        when(gateway.finalizeOrder(any(), eq("order-observation"))).thenReturn(
                new SeckillRedisReservationGateway.FinalizeResult(
                        SeckillRedisReservationGateway.FinalizeCode.FINALIZED));
        SeckillProcessingService processing = mock(SeckillProcessingService.class);
        AtomicReference<Map<String, String>> businessMdc = new AtomicReference<>();
        when(processing.createOrder(anyString(), anyString(), any())).thenAnswer(ignored -> {
            businessMdc.set(MDC.getCopyOfContextMap());
            return new SeckillProcessingService.ProcessOutcome(
                    SeckillProcessingService.OutcomeType.ORDER_CREATED,
                    "order-observation", null, null);
        });
        SeckillOrderMetrics metrics = mock(SeckillOrderMetrics.class);
        when(metrics.consumed()).thenReturn(mock(Counter.class));
        when(metrics.processed()).thenReturn(mock(Counter.class));
        when(metrics.conversionLatency()).thenThrow(
                new IllegalStateException("timer registry unavailable"));
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder()).thenThrow(new IllegalStateException("tracer unavailable"));
        ReservationStreamConsumer consumer = new ReservationStreamConsumer(
                redis, new SeckillOrderProperties(), gateway, processing,
                mock(SeckillProcessingFailpoint.class), metrics, tracer);
        Map<String, String> previous = Map.of(
                "requestId", "outer-request",
                "traceId", "a".repeat(32),
                "spanId", "b".repeat(16),
                "tracestate", "outer=value"
        );
        MDC.setContextMap(previous);
        try {
            consumer.process(SeckillRedisKeys.reservationStream(41), "1-0", event(), false);
            assertThat(businessMdc.get()).containsEntry(
                            "requestId", "consumer-observation-request")
                    .doesNotContainKeys("traceId", "spanId", "tracestate");
            assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(previous);
        } finally {
            MDC.clear();
        }

        verify(processing).createOrder(
                eq(SeckillRedisKeys.reservationStream(41)), eq("1-0"), any());
        verify(gateway).finalizeOrder(any(), eq("order-observation"));
        verify(streams).acknowledge(
                SeckillRedisKeys.reservationStream(41), "hotshop-order-v1", "1-0");
    }

    private static Map<Object, Object> event() {
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", "1");
        values.put("eventType", "RESERVATION_ACCEPTED");
        values.put("eventId", "evt_" + "1".repeat(32));
        values.put("reservationNo", "rsv_" + "2".repeat(32));
        values.put("activityId", "41");
        values.put("userId", "42");
        values.put("productId", "43");
        values.put("quantity", "1");
        values.put("unitPrice", "10.00");
        values.put("currency", "CNY");
        values.put("status", "RESERVED");
        values.put("requestId", "consumer-observation-request");
        values.put("traceparent", "");
        values.put("tracestate", "");
        values.put("occurredAtMs", Long.toString(Instant.now().toEpochMilli()));
        values.put("activityVersion", "1");
        values.put("idempotencyKeyHash", "3".repeat(64));
        values.put("requestFingerprint", "4".repeat(64));
        return values;
    }
}
