package com.real.task.seckill;

import com.real.infrastructure.redis.HotShopRedisProperties;
import com.real.infrastructure.redis.RedisConnectionsConfiguration;
import com.real.infrastructure.redis.SeckillRedisKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Testcontainers
class ReservationStreamPollingContainerTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.3-alpine")
            .withExposedPorts(6379);
    private static final String STREAM = SeckillRedisKeys.reservationStream(41);
    private static final String ORDER = "order-polling";
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimpleMeterRegistry registry;
    private SeckillOrderMetrics metrics;
    private SeckillOrderProperties properties;
    private SeckillProcessingService processing;
    private ReservationStreamConsumer consumer;

    @BeforeAll
    static void connect() {
        HotShopRedisProperties redisProperties = new HotShopRedisProperties();
        redisProperties.getSeckill().setHost(REDIS.getHost());
        redisProperties.getSeckill().setPort(REDIS.getMappedPort(6379));
        connectionFactory = new RedisConnectionsConfiguration()
                .seckillRedisConnectionFactory(redisProperties);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void prepare() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        registry = new SimpleMeterRegistry();
        metrics = new SeckillOrderMetrics(registry);
        properties = new SeckillOrderProperties();
        var gateway = mock(SeckillRedisReservationGateway.class);
        when(gateway.verify(any())).thenReturn(
                new SeckillRedisReservationGateway.ReservationProof(true, "RESERVED", null));
        when(gateway.finalizeOrder(any(), eq(ORDER))).thenReturn(
                new SeckillRedisReservationGateway.FinalizeResult(
                        SeckillRedisReservationGateway.FinalizeCode.FINALIZED));
        processing = mock(SeckillProcessingService.class);
        when(processing.createOrder(anyString(), anyString(), any()))
                .thenReturn(SeckillProcessingService.ProcessOutcome.created(ORDER));
        consumer = new ReservationStreamConsumer(redis, properties, gateway, processing,
                mock(SeckillProcessingFailpoint.class), metrics);
        redis.opsForSet().add(SeckillRedisKeys.reservationStreamRegistry(), STREAM);
        consumer.refreshStreams();
    }

    @AfterEach
    void closeMeters() {
        if (registry != null) registry.close();
    }

    @Test
    void normalIdleReadsDoNotCountAsProcessingFailures() {
        consumer.poll();
        consumer.poll();

        assertThat(metrics.failures().count()).isZero();
        assertThat(metrics.consumed().count()).isZero();
        verifyNoInteractions(processing);
    }

    @Test
    void messageArrivingDuringBlockingReadIsDeliveredAndAcknowledged() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var polling = executor.submit(consumer::poll);
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).until(() ->
                    Integer.parseInt(redis.execute((RedisCallback<String>) connection ->
                            connection.serverCommands().info("clients")
                                    .getProperty("blocked_clients"))) > 0);
            appendEvent();
            polling.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertDeliveredOnce();
        assertThat(metrics.failures().count()).isZero();
    }

    @Test
    void realRedisOutageIsCountedAndNextPollRecovers() {
        String containerId = REDIS.getContainerId();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(containerId).exec();
        try {
            consumer.poll();
            assertThat(metrics.failures().count()).isEqualTo(1);
            verifyNoInteractions(processing);
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() ->
                "PONG".equals(redis.execute((RedisCallback<String>) connection -> connection.ping())));

        appendEvent();
        consumer.poll();

        assertDeliveredOnce();
        assertThat(metrics.failures().count()).isEqualTo(1);
    }

    private void assertDeliveredOnce() {
        verify(processing).createOrder(eq(STREAM), anyString(), any());
        assertThat(metrics.processed().count()).isEqualTo(1);
        assertThat(redis.opsForStream().pending(STREAM, properties.getGroupName())
                .getTotalPendingMessages()).isZero();
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(1);
    }

    private void appendEvent() {
        Map<String, String> values = new LinkedHashMap<>();
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
        values.put("requestId", "consumer-polling-request");
        values.put("traceparent", "");
        values.put("tracestate", "");
        values.put("occurredAtMs", Long.toString(Instant.now().toEpochMilli()));
        values.put("activityVersion", "1");
        values.put("idempotencyKeyHash", "3".repeat(64));
        values.put("requestFingerprint", "4".repeat(64));
        redis.opsForStream().add(MapRecord.create(STREAM, values));
    }
}
