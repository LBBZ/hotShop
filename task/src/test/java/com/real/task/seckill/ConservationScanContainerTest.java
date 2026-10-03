package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ConservationScanContainerTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.3-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static final long ACTIVITY = 901;
    private static final String META = SeckillRedisKeys.activityMetadata(ACTIVITY);
    private static final String STOCK = SeckillRedisKeys.availableStock(ACTIVITY);
    private static final String STREAM = SeckillRedisKeys.reservationStream(ACTIVITY);
    private static final String CHECKPOINT = SeckillRedisKeys.conservationCheckpoint(ACTIVITY);
    private static final String SEEN = SeckillRedisKeys.conservationSeen(ACTIVITY);

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void reset() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        redis.opsForHash().putAll(META, Map.of("databaseVersion", "1", "inventoryRevision", "0",
                "initialAvailableStock", "100"));
        redis.opsForValue().set(STOCK, "100");
    }

    @Test
    void continuousWritersDoNotRestartThePrefixOrProduceACompletedAudit() {
        for (int i = 1; i <= 4; i++) accept(i, 1);
        String initialTail = redis.opsForStream().info(STREAM).lastGeneratedId();
        assertThat(page(2, 100).getFirst()).isEqualTo("IN_PROGRESS");
        Object firstCursor = redis.opsForHash().get(CHECKPOINT, "cursor");
        accept(5, 1);
        assertThat(page(2, 100).getFirst()).isEqualTo("IN_PROGRESS");
        assertThat(redis.opsForHash().get(CHECKPOINT, "cursor")).isNotEqualTo(firstCursor);
        assertThat(redis.opsForHash().get(CHECKPOINT, "upperBound")).isEqualTo(initialTail);
        accept(6, 1);
        assertThat(page(2, 100).getFirst()).isEqualTo("INCONCLUSIVE");
        assertThat(redis.opsForHash().get(CHECKPOINT, "reason")).isEqualTo("WRITES_DURING_SCAN");
        assertThat(redis.opsForHash().get(CHECKPOINT, "completedScans")).isNull();
        assertThat(redis.hasKey(SEEN)).isFalse();
        assertThat(redis.opsForValue().get(STOCK)).isEqualTo("94");

        // With writes stopped, a fresh cycle must inspect all six reservations.
        for (int i = 0; i < 4; i++) page(2, 100);
        assertThat(redis.opsForHash().get(CHECKPOINT, "completedScans")).isEqualTo("1");
        assertThat(redis.opsForHash().get(CHECKPOINT, "lastCompletedQuantity")).isEqualTo("6");
    }

    @Test
    void distinctReservationLimitStopsWithoutGrowingTheSeenHashOrChangingStock() {
        for (int i = 1; i <= 5; i++) accept(i, 1);
        assertThat(page(2, 3).getFirst()).isEqualTo("IN_PROGRESS");
        assertThat(redis.opsForHash().size(SEEN)).isEqualTo(3); // Two facts plus the initialization marker.
        assertThat(page(2, 3).getFirst()).isEqualTo("INCONCLUSIVE");
        assertThat(redis.opsForHash().get(CHECKPOINT, "reason")).isEqualTo("RESERVATION_LIMIT");
        assertThat(redis.opsForHash().get(CHECKPOINT, "lastSeenCount")).isEqualTo("3");
        assertThat(redis.hasKey(SEEN)).isFalse();
        assertThat(redis.opsForHash().get(CHECKPOINT, "completedScans")).isNull();
        assertThat(redis.opsForValue().get(STOCK)).isEqualTo("95");
        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(5);
    }

    @Test
    void duplicatesAtTheLimitAcrossPagesDoNotConsumeAnotherSlotOrQuantity() {
        accept(1, 2);
        delivery(1, 2);
        accept(2, 3);
        delivery(1, 2);
        delivery(2, 3);
        for (int i = 0; i < 3; i++) page(2, 2);
        assertThat(redis.opsForHash().get(CHECKPOINT, "state")).isEqualTo("COMPLETE");
        assertThat(redis.opsForHash().get(CHECKPOINT, "lastCompletedQuantity")).isEqualTo("5");
        assertThat(redis.opsForHash().get(CHECKPOINT, "lastCompletedInvalid")).isEqualTo("0");
        assertThat(redis.opsForValue().get(STOCK)).isEqualTo("95");
        assertThat(redis.hasKey(SEEN)).isFalse();
    }

    @Test
    void idleDedupStateExpiresAndMissingStateCannotReuseItsPartialSum() {
        for (int i = 1; i <= 3; i++) accept(i, 1);
        page(2, 3);
        assertThat(redis.getExpire(SEEN)).isPositive().isLessThanOrEqualTo(86_400L);
        redis.delete(SEEN); // Same observable state as idle expiration.
        page(2, 3);
        assertThat(redis.opsForHash().get(CHECKPOINT, "quantity")).isEqualTo("2");
        page(2, 3);
        assertThat(redis.opsForHash().get(CHECKPOINT, "lastCompletedQuantity")).isEqualTo("3");
        assertThat(redis.opsForValue().get(STOCK)).isEqualTo("97");
    }

    @Test
    void loweringTheLimitCannotResumeAnOversizedDedupTable() {
        for (int i = 1; i <= 5; i++) accept(i, 1);
        page(3, 10);
        assertThat(redis.opsForHash().size(SEEN)).isEqualTo(4);
        assertThat(page(2, 2).getFirst()).isEqualTo("INCONCLUSIVE");
        assertThat(redis.opsForHash().get(CHECKPOINT, "reason")).isEqualTo("RESERVATION_LIMIT");
        assertThat(redis.hasKey(SEEN)).isFalse();
        assertThat(redis.opsForValue().get(STOCK)).isEqualTo("95");
    }

    @SuppressWarnings("unchecked")
    private List<String> page(int records, int distinctReservations) {
        // Exercise the script selected by the production service, including on the baseline.
        var script = (DefaultRedisScript<List>) ReflectionTestUtils.getField(
                SeckillReconciliationService.class, "CONSERVATION_PAGE");
        return redis.execute(script, List.of(META, STOCK, STREAM, CHECKPOINT, SEEN),
                Integer.toString(records), SeckillRedisKeys.reservation(ACTIVITY, ""),
                Integer.toString(distinctReservations));
    }

    private void accept(int id, int quantity) {
        redis.opsForHash().putAll(SeckillRedisKeys.reservation(ACTIVITY, "rsv_" + id),
                Map.of("reservationNo", "rsv_" + id, "quantity", Integer.toString(quantity), "status", "RESERVED"));
        redis.opsForHash().increment(META, "inventoryRevision", 1);
        redis.opsForValue().decrement(STOCK, quantity);
        delivery(id, quantity);
    }

    private void delivery(int id, int quantity) {
        redis.opsForStream().add(MapRecord.create(STREAM,
                Map.of("reservationNo", "rsv_" + id, "quantity", Integer.toString(quantity))));
    }
}
