package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReservationStreamLagTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SeckillOrderMetrics metrics = new SeckillOrderMetrics(meters);
    private final String stream = SeckillRedisKeys.reservationStream(10);
    private final ReservationStreamConsumer consumer = new ReservationStreamConsumer(redis,
            new SeckillOrderProperties(), mock(SeckillRedisReservationGateway.class),
            mock(SeckillProcessingService.class), mock(SeckillProcessingFailpoint.class), metrics);

    @BeforeEach
    void prepare() {
        when(redis.opsForStream()).thenReturn(streams);
        ReflectionTestUtils.setField(consumer, "streams", List.of(stream));
    }

    @Test
    void largeLagUsesMetadataWithoutReadingMessagesOrTruncating() {
        when(streams.groups(stream)).thenReturn(groups(50_000L));

        consumer.refreshLag();

        assertThat(meters.get("hotshop.seckill.stream.lag").gauge().value()).isEqualTo(50_000);
        assertThat(meters.get("hotshop.seckill.stream.lag.known").gauge().value()).isEqualTo(1);
        verify(streams).groups(stream);
        verifyNoMoreInteractions(streams);
    }

    @Test
    void unknownLagPreservesLastSampleAndExplicitlyMarksItUnavailable() {
        metrics.streamLag(42);
        when(streams.groups(stream)).thenReturn(groups(null));

        consumer.refreshLag();

        assertThat(meters.get("hotshop.seckill.stream.lag").gauge().value()).isEqualTo(42);
        assertThat(meters.get("hotshop.seckill.stream.lag.known").gauge().value()).isZero();
    }

    @Test
    void lagObservationFailureDoesNotEscapeIntoBusinessScheduler() {
        when(streams.groups(stream)).thenThrow(new IllegalStateException("unavailable"));

        consumer.refreshLag();

        assertThat(meters.get("hotshop.seckill.stream.lag.known").gauge().value()).isZero();
    }

    private StreamInfo.XInfoGroups groups(Long lag) {
        return StreamInfo.XInfoGroups.fromList(List.of(Arrays.asList(
                "name", "hotshop-order-v1", "consumers", 1L, "pending", 0L,
                "last-delivered-id", "0-0", "entries-read", 0L, "lag", lag)));
    }
}
