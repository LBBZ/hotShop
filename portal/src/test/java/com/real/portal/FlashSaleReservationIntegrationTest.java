package com.real.portal;

import com.real.domain.service.seckill.FlashSaleActivityLoader;
import com.real.domain.service.seckill.FlashSaleLoadCode;
import com.real.domain.service.seckill.FlashSaleLoadResult;
import com.real.domain.service.seckill.FlashSaleReservationCode;
import com.real.domain.service.seckill.FlashSaleReservationResult;
import com.real.domain.service.seckill.FlashSaleReservationService;
import com.real.domain.service.seckill.FlashSaleReservationStatusService;
import com.real.domain.userjourney.TransactionTimelineWriter;
import com.real.portal.timeline.TransactionTimelineService;
import com.real.common.api.ApiException;
import com.real.common.api.dto.TransactionTimelineEventResponse;
import com.real.infrastructure.redis.SeckillRedisKeys;
import com.real.security.entity.CustomUserDetails;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false",
        "hotshop.redis.cache.timeout=2s",
        "hotshop.redis.seckill.timeout=2s",
        "hotshop.seckill.reservation-ttl=7d",
        "hotshop.seckill.idempotency-ttl=24h",
        "hotshop.seckill.activity-retention=7d"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlashSaleReservationIntegrationTest {
    private static final long ACTIVITY_ID = 7001L;
    private static final long SECOND_ACTIVITY_ID = 7002L;
    private static final long PRODUCT_ID = 8001L;

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotShop")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withUrlParam("connectTimeout", "5000")
            .withCommand("--log-bin-trust-function-creators=1");
    static final GenericContainer<?> CACHE =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.1-alpine"))
                    .withExposedPorts(6379);
    static final GenericContainer<?> SECKILL =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.1-alpine"))
                    .withCommand(
                            "redis-server",
                            "--databases", "1",
                            "--maxmemory-policy", "noeviction",
                            "--appendonly", "yes"
                    )
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        CACHE.start();
        SECKILL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> 60_000);
        registry.add("hotshop.redis.cache.host", CACHE::getHost);
        registry.add("hotshop.redis.cache.port", () -> CACHE.getMappedPort(6379));
        registry.add("hotshop.redis.cache.password", () -> "");
        registry.add("hotshop.redis.seckill.host", SECKILL::getHost);
        registry.add("hotshop.redis.seckill.port", () -> SECKILL.getMappedPort(6379));
        registry.add("hotshop.redis.seckill.password", () -> "");
    }

    @Autowired
    FlashSaleActivityLoader loader;
    @Autowired
    FlashSaleReservationService reservationService;
    @Autowired
    FlashSaleReservationStatusService reservationStatusService;
    @Autowired
    TransactionTimelineService timelineService;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    @Qualifier("cacheStringRedisTemplate")
    StringRedisTemplate cacheRedis;
    @Autowired
    @Qualifier("seckillStringRedisTemplate")
    StringRedisTemplate seckillRedis;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void resetFacts() throws Exception {
        SECKILL.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory", "0");
        SECKILL.execInContainer("redis-cli", "CONFIG", "SET", "maxmemory-policy", "noeviction");
        seckillRedis.getConnectionFactory().getConnection().serverCommands().flushDb();
        cacheRedis.getConnectionFactory().getConnection().serverCommands().flushDb();
        jdbcTemplate.update("DELETE FROM user_transaction_timeline");
        jdbcTemplate.update("DELETE FROM order_purchase_intent");
        jdbcTemplate.update("DELETE FROM payment_order");
        jdbcTemplate.update("DELETE FROM sales_order_item");
        jdbcTemplate.update("DELETE FROM sales_order");
        jdbcTemplate.update("DELETE FROM sale_reservation");
        jdbcTemplate.update("DELETE FROM flash_sale_activity");
        jdbcTemplate.update("DELETE FROM catalog_product");
        insertProduct();
    }

    @AfterAll
    void closeRedisClientsBeforeRyukContainerCleanup() {
        destroyConnectionFactory(cacheRedis);
        destroyConnectionFactory(seckillRedis);
    }

    @Test
    @Order(1)
    void allLuaKeysUseOneVersionedClusterSlotAndInstancesStayIsolated() {
        String hash = FlashSaleReservationService.sha256("slot-test-idempotency");
        List<String> keys = List.of(
                SeckillRedisKeys.activityMetadata(ACTIVITY_ID),
                SeckillRedisKeys.availableStock(ACTIVITY_ID),
                SeckillRedisKeys.userReservation(ACTIVITY_ID, 91),
                SeckillRedisKeys.idempotency(91, hash),
                SeckillRedisKeys.reservation(ACTIVITY_ID, "rsv_0123456789abcdef0123456789abcdef"),
                SeckillRedisKeys.reservationStream(ACTIVITY_ID),
                SeckillRedisKeys.reservationStreamRegistry()
        );
        Set<Integer> slots = new HashSet<>();
        keys.forEach(key -> slots.add(SeckillRedisKeys.clusterSlot(key)));
        assertThat(slots).hasSize(1);

        cacheRedis.opsForValue().set("task07:isolation", "cache");
        seckillRedis.opsForValue().set("task07:isolation", "seckill");
        assertThat(cacheRedis.opsForValue().get("task07:isolation")).isEqualTo("cache");
        assertThat(seckillRedis.opsForValue().get("task07:isolation")).isEqualTo("seckill");
        assertThat(((LettuceConnectionFactory) cacheRedis.getConnectionFactory()).getDatabase())
                .isZero();
        assertThat(((LettuceConnectionFactory) seckillRedis.getConnectionFactory()).getDatabase())
                .isZero();
        assertThat(((LettuceConnectionFactory) cacheRedis.getConnectionFactory()).getPort())
                .isNotEqualTo(
                        ((LettuceConnectionFactory) seckillRedis.getConnectionFactory()).getPort()
                );
        assertThat(cacheRedis.getConnectionFactory().getConnection().getNativeConnection())
                .isNotSameAs(seckillRedis.getConnectionFactory().getConnection().getNativeConnection());
    }

    @Test
    @Order(2)
    void loaderIsVersionedIdempotentAndReconcilesMySqlRedisAndStream() {
        insertActivity(ACTIVITY_ID, 5, 5, 2, "ACTIVE", -60, 600, 3);

        FlashSaleLoadResult loaded = loader.load(ACTIVITY_ID);
        FlashSaleLoadResult replay = loader.load(ACTIVITY_ID);

        assertThat(loaded.code()).isEqualTo(FlashSaleLoadCode.LOADED);
        assertThat(loaded.consistent()).isTrue();
        assertThat(replay.code()).isEqualTo(FlashSaleLoadCode.IDEMPOTENT);
        assertThat(replay.redisVersion()).isEqualTo(3);
        assertThat(replay.redisAvailableStock()).isEqualTo(5);
        assertThat(seckillRedis.opsForSet().isMember(
                SeckillRedisKeys.reservationStreamRegistry(),
                SeckillRedisKeys.reservationStream(ACTIVITY_ID)
        )).isTrue();

        jdbcTemplate.update(
                "UPDATE flash_sale_activity SET version = 2 WHERE activity_id = ?",
                ACTIVITY_ID
        );
        assertThat(loader.load(ACTIVITY_ID).code()).isEqualTo(FlashSaleLoadCode.STALE_VERSION);

        assertThat(reservationService.reserve(
                ACTIVITY_ID,
                99,
                1,
                "loader-reservation-key-00000000000000000001",
                "loader-reservation"
        ).code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);
        jdbcTemplate.update(
                "UPDATE flash_sale_activity SET version = 4 WHERE activity_id = ?",
                ACTIVITY_ID
        );
        assertThat(loader.load(ACTIVITY_ID).code())
                .isEqualTo(FlashSaleLoadCode.RESERVATIONS_EXIST);

        String metadataKey = SeckillRedisKeys.activityMetadata(ACTIVITY_ID);
        String stockKey = SeckillRedisKeys.availableStock(ACTIVITY_ID);
        String stockAfterReservation = seckillRedis.opsForValue().get(stockKey);
        seckillRedis.delete(metadataKey);
        assertThat(loader.load(ACTIVITY_ID).code())
                .isEqualTo(FlashSaleLoadCode.RESERVATIONS_EXIST);
        assertThat(seckillRedis.opsForValue().get(stockKey)).isEqualTo(stockAfterReservation);
    }

    @Test
    @Order(3)
    void twoHundredConcurrentUsersNeverOversellAndEveryEffectReconciles() throws Exception {
        insertActivity(ACTIVITY_ID, 50, 50, 1, "ACTIVE", -60, 600, 1);
        assertThat(loader.load(ACTIVITY_ID).code()).isEqualTo(FlashSaleLoadCode.LOADED);
        long reservationsBefore = count("sale_reservation");
        long ordersBefore = count("sales_order");
        long outboxBefore = count("outbox_event");

        List<Callable<FlashSaleReservationResult>> calls = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            long userId = 10_000L + index;
            String key = "concurrent-key-" + String.format("%032d", index);
            calls.add(() -> reservationService.reserve(
                    ACTIVITY_ID, userId, 1, key, "req-" + userId
            ));
        }
        List<FlashSaleReservationResult> results;
        try (var executor = Executors.newFixedThreadPool(32)) {
            results = executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }

        long accepted = results.stream()
                .filter(result -> result.code() == FlashSaleReservationCode.ACCEPTED)
                .count();
        long streamEvents = streamLength(ACTIVITY_ID);
        assertThat(accepted).isEqualTo(50);
        assertThat(stock(ACTIVITY_ID)).isZero();
        assertThat(accepted).isEqualTo(streamEvents);
        assertThat(reservationRecordCount(ACTIVITY_ID)).isEqualTo(accepted);
        assertThat(loader.load(ACTIVITY_ID).consistent()).isTrue();
        assertThat(count("sale_reservation")).isEqualTo(reservationsBefore);
        assertThat(count("sales_order")).isEqualTo(ordersBefore);
        assertThat(count("outbox_event")).isEqualTo(outboxBefore);
    }

    @Test
    @Order(4)
    void sameUserAndSameIdempotencyKeyHaveExactlyOneBusinessEffect() throws Exception {
        insertActivity(ACTIVITY_ID, 10, 10, 1, "ACTIVE", -60, 600, 1);
        loader.load(ACTIVITY_ID);

        List<Callable<FlashSaleReservationResult>> sameUser = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            String key = "same-user-key-" + String.format("%032d", index);
            sameUser.add(() -> reservationService.reserve(
                    ACTIVITY_ID, 201L, 1, key, "same-user-" + key.substring(key.length() - 4)
            ));
        }
        List<FlashSaleReservationResult> userResults = invoke(sameUser);
        assertThat(userResults.stream().filter(r -> r.code() == FlashSaleReservationCode.ACCEPTED))
                .hasSize(1);
        assertThat(streamLength(ACTIVITY_ID)).isEqualTo(1);
        assertThat(stock(ACTIVITY_ID)).isEqualTo(9);

        resetSeckillAndReload(ACTIVITY_ID);
        String idempotencyKey = "same-idempotency-key-000000000000000000000001";
        List<Callable<FlashSaleReservationResult>> replays = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            replays.add(() -> reservationService.reserve(
                    ACTIVITY_ID, 202L, 1, idempotencyKey, "same-idem-request"
            ));
        }
        List<FlashSaleReservationResult> replayResults = invoke(replays);
        assertThat(replayResults.stream().filter(r -> r.code() == FlashSaleReservationCode.ACCEPTED))
                .hasSize(1);
        assertThat(replayResults.stream()
                .filter(r -> r.code() == FlashSaleReservationCode.IDEMPOTENT_REPLAY)).hasSize(31);
        assertThat(replayResults.stream().map(FlashSaleReservationResult::reservationNo).distinct())
                .hasSize(1);
        assertThat(streamLength(ACTIVITY_ID)).isEqualTo(1);
        assertThat(stock(ACTIVITY_ID)).isEqualTo(9);
    }

    @Test
    @Order(5)
    void changedQuantityOrActivityConflictsAndSoldOutAddsNoEvents() {
        insertActivity(ACTIVITY_ID, 2, 1, 2, "ACTIVE", -60, 600, 1);
        insertActivity(SECOND_ACTIVITY_ID, 5, 5, 2, "ACTIVE", -60, 600, 1);
        loader.load(ACTIVITY_ID);
        loader.load(SECOND_ACTIVITY_ID);
        String key = "cross-activity-idempotency-00000000000000000001";

        assertThat(reservationService.reserve(
                ACTIVITY_ID,
                300,
                3,
                "over-limit-key-00000000000000000000000001",
                "over-limit-request"
        ).code()).isEqualTo(FlashSaleReservationCode.USER_LIMIT_REACHED);
        assertThat(reservationService.reserve(ACTIVITY_ID, 301, 1, key, "request-a").code())
                .isEqualTo(FlashSaleReservationCode.ACCEPTED);
        assertThat(reservationService.reserve(ACTIVITY_ID, 301, 2, key, "request-b").code())
                .isEqualTo(FlashSaleReservationCode.IDEMPOTENCY_CONFLICT);
        assertThat(reservationService.reserve(SECOND_ACTIVITY_ID, 301, 1, key, "request-c").code())
                .isEqualTo(FlashSaleReservationCode.IDEMPOTENCY_CONFLICT);
        long events = streamLength(ACTIVITY_ID);
        assertThat(reservationService.reserve(
                ACTIVITY_ID,
                302,
                1,
                "sold-out-key-000000000000000000000001",
                "sold-out-request"
        ).code()).isEqualTo(FlashSaleReservationCode.SOLD_OUT);
        assertThat(streamLength(ACTIVITY_ID)).isEqualTo(events);
    }

    @Test
    @Order(6)
    void redisServerTimeControlsStartAndEndBoundaries() {
        insertActivity(ACTIVITY_ID, 2, 2, 1, "ACTIVE", 60, 600, 1);
        loader.load(ACTIVITY_ID);
        assertThat(reservationService.reserve(
                ACTIVITY_ID, 401, 1, "future-time-key-000000000000000000000001", "future"
        ).code()).isEqualTo(FlashSaleReservationCode.ACTIVITY_NOT_STARTED);

        resetSeckill();
        jdbcTemplate.update(
                "UPDATE flash_sale_activity SET starts_at = ?, ends_at = ?, version = 2 WHERE activity_id = ?",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.SECONDS)),
                ACTIVITY_ID
        );
        loader.load(ACTIVITY_ID);
        assertThat(reservationService.reserve(
                ACTIVITY_ID, 401, 1, "ended-time-key-0000000000000000000000001", "ended"
        ).code()).isEqualTo(FlashSaleReservationCode.ACTIVITY_ENDED);
    }

    @Test
    @Order(7)
    void streamCarriesTraceContextAtomicallyWithoutRequestIdCorrelation() {
        insertActivity(ACTIVITY_ID, 3, 3, 1, "ACTIVE", -60, 600, 1);
        loader.load(ACTIVITY_ID);
        String sharedRequestId = "shared-request-id";
        FlashSaleReservationService target = AopTestUtils.getTargetObject(reservationService);
        try {
            MDC.put("traceId", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            MDC.put("spanId", "1111111111111111");
            MDC.put("tracestate", "vendor=first");
            assertThat(target.reserve(
                    ACTIVITY_ID, 701, 1,
                    "trace-user-one-0000000000000000000000001", sharedRequestId
            ).code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);

            MDC.put("traceId", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            MDC.put("spanId", "2222222222222222");
            MDC.put("tracestate", "vendor=second");
            assertThat(target.reserve(
                    ACTIVITY_ID, 702, 1,
                    "trace-user-two-0000000000000000000000001", sharedRequestId
            ).code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);
        } finally {
            MDC.clear();
        }

        List<MapRecord<String, Object, Object>> events = seckillRedis.opsForStream().range(
                SeckillRedisKeys.reservationStream(ACTIVITY_ID),
                org.springframework.data.domain.Range.unbounded()
        );
        assertThat(events).hasSize(2);
        assertThat(events).extracting(event -> event.getValue().get("requestId"))
                .containsOnly(sharedRequestId);
        assertThat(events).extracting(event -> event.getValue().get("traceparent"))
                .containsExactly(
                        "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1111111111111111-01",
                        "00-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-2222222222222222-01"
                );
        assertThat(events).extracting(event -> event.getValue().get("tracestate"))
                .containsExactly("vendor=first", "vendor=second");
    }

    @Test
    @Order(8)
    void wrongTypeAndNoevictionOomLeaveNoOrphanOrSilentEventLoss() throws Exception {
        insertActivity(ACTIVITY_ID, 3, 3, 1, "ACTIVE", -60, 600, 1);
        loader.load(ACTIVITY_ID);
        String stockKey = SeckillRedisKeys.availableStock(ACTIVITY_ID);
        seckillRedis.delete(stockKey);
        seckillRedis.opsForHash().put(stockKey, "wrong", "type");
        FlashSaleReservationResult wrongType = reservationService.reserve(
                ACTIVITY_ID,
                501,
                1,
                "wrong-type-key-0000000000000000000000001",
                "wrong-type"
        );
        assertThat(wrongType.code()).isEqualTo(FlashSaleReservationCode.INTERNAL_STATE_INVALID);
        assertThat(streamLength(ACTIVITY_ID)).isZero();
        assertThat(reservationRecordCount(ACTIVITY_ID)).isZero();

        resetSeckillAndReload(ACTIVITY_ID);
        reservationService.reserve(
                ACTIVITY_ID,
                502,
                0,
                "warm-script-key-0000000000000000000000001",
                "warm-script"
        );
        String replayKey = "oom-replay-key-0000000000000000000000001";
        FlashSaleReservationResult accepted = reservationService.reserve(
                ACTIVITY_ID,
                503,
                1,
                replayKey,
                "oom-replay-original"
        );
        assertThat(accepted.code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);
        long stockBefore = stock(ACTIVITY_ID);
        SECKILL.execInContainer(
                "redis-cli", "CONFIG", "SET", "maxmemory", "1"
        );
        FlashSaleReservationResult replay = reservationService.reserve(
                ACTIVITY_ID,
                503,
                1,
                replayKey,
                "same-client-request-id"
        );
        assertThat(replay.code()).isEqualTo(FlashSaleReservationCode.IDEMPOTENT_REPLAY);
        assertThat(replay.reservationNo()).isEqualTo(accepted.reservationNo());
        assertThat(replay.requestId()).isEqualTo("oom-replay-original");
        FlashSaleReservationResult oom = reservationService.reserve(
                ACTIVITY_ID,
                504,
                1,
                "oom-failure-key-0000000000000000000000001",
                "oom-failure"
        );
        assertThat(oom.code()).isEqualTo(FlashSaleReservationCode.INTERNAL_STATE_INVALID);
        assertThat(stock(ACTIVITY_ID)).isEqualTo(stockBefore);
        assertThat(streamLength(ACTIVITY_ID)).isOne();
        assertThat(reservationRecordCount(ACTIVITY_ID)).isOne();
    }

    @Test
    @Order(8)
    void cacheFailureDoesNotPolluteSeckillAndSeckillFailureReturnsSanitized503() throws Exception {
        insertActivity(ACTIVITY_ID, 2, 2, 1, "ACTIVE", -60, 600, 1);
        loader.load(ACTIVITY_ID);
        cacheRedis.opsForValue().set("cache-only-fact", "cache");
        seckillRedis.opsForValue().set("seckill-only-fact", "seckill");

        CACHE.getDockerClient().pauseContainerCmd(CACHE.getContainerId()).exec();
        try {
            assertThat(seckillRedis.opsForValue().get("seckill-only-fact")).isEqualTo("seckill");
            assertThat(reservationService.reserve(
                    ACTIVITY_ID,
                    601,
                    1,
                    "cache-failure-key-000000000000000000000001",
                    "cache-failure"
            ).code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);
        } finally {
            CACHE.getDockerClient().unpauseContainerCmd(CACHE.getContainerId()).exec();
        }

        CustomUserDetails principal = CustomUserDetails.builder()
                .userId(602L)
                .username("task07-user")
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        SECKILL.getDockerClient().pauseContainerCmd(SECKILL.getContainerId()).exec();
        try {
            assertTimeout(Duration.ofSeconds(10), () ->
                    mockMvc.perform(post(
                                            "/api/v1/flash-sales/{activityId}/reservations",
                                            ACTIVITY_ID
                                    )
                                    .with(user(principal))
                                    .header(
                                            "Idempotency-Key",
                                            "seckill-down-key-000000000000000000000001"
                                    )
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"quantity\":1}"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.code").value("SECKILL_SERVICE_UNAVAILABLE"))
                            .andExpect(jsonPath("$.detail").value(
                                    "The flash-sale reservation service is temporarily unavailable"
                            ))
            );
        } finally {
            SECKILL.getDockerClient().unpauseContainerCmd(SECKILL.getContainerId()).exec();
        }
        assertThat(count("sale_reservation")).isZero();
        assertThat(count("sales_order")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    @Order(9)
    void ownedStatusFallsBackToRedisThenPrefersMysqlAndHidesOtherUsers() {
        insertActivity(ACTIVITY_ID, 2, 2, 1, "ACTIVE", -60, 600, 1);
        assertThat(loader.load(ACTIVITY_ID).code()).isEqualTo(FlashSaleLoadCode.LOADED);
        FlashSaleReservationResult accepted = reservationService.reserve(
                ACTIVITY_ID,
                701,
                1,
                "status-query-key-000000000000000000000001",
                "status-query"
        );
        assertThat(accepted.code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);

        assertThat(reservationStatusService.findOwned(
                ACTIVITY_ID,
                accepted.reservationNo(),
                701
        )).satisfies(response -> {
            assertThat(response.status()).isEqualTo("RESERVED");
            assertThat(response.orderId()).isNull();
        });
        assertThat(reservationStatusService.findOwned(
                ACTIVITY_ID,
                accepted.reservationNo(),
                702
        )).isNull();

        Map<Object, Object> facts = seckillRedis.opsForHash().entries(
                SeckillRedisKeys.reservation(ACTIVITY_ID, accepted.reservationNo())
        );
        jdbcTemplate.update("""
                INSERT INTO sale_reservation (
                    reservation_no, activity_id, user_id, product_id,
                    quantity, unit_price, reserved_amount, currency,
                    activity_version, idempotency_key_hash, request_fingerprint,
                    reserved_at, status, order_id, expires_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 'CNY', ?, ?, ?,
                    FROM_UNIXTIME(? / 1000.0),
                    'ORDER_CREATED', 'ord_0123456789abcdef0123456789abcdef',
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 15 MINUTE)
                )
                """,
                facts.get("reservationNo"),
                facts.get("activityId"),
                facts.get("userId"),
                facts.get("productId"),
                facts.get("quantity"),
                facts.get("unitPrice"),
                new BigDecimal(String.valueOf(facts.get("unitPrice"))),
                facts.get("activityVersion"),
                facts.get("idempotencyKeyHash"),
                facts.get("requestFingerprint"),
                facts.get("reservedAtMs")
        );

        assertThat(reservationStatusService.findOwned(
                ACTIVITY_ID,
                accepted.reservationNo(),
                701
        )).satisfies(response -> {
            assertThat(response.status()).isEqualTo("ORDER_CREATED");
            assertThat(response.orderId())
                    .isEqualTo("ord_0123456789abcdef0123456789abcdef");
        });
    }

    @Test
    @Order(10)
    void durableTimelineRecoversAfterLastEventIdAndServiceRecreation() {
        String orderId = "order_timeline_1";
        long ownerId = 611L;
        Instant orderCreatedAt = Instant.parse("2026-08-08T06:00:00Z");
        Instant paymentCreatedAt = orderCreatedAt.plusSeconds(1);
        Instant paidAt = orderCreatedAt.plusSeconds(2);
        jdbcTemplate.update("""
                INSERT INTO sales_order (order_id, user_id, total_amount, currency, status)
                VALUES (?, ?, 19.90, 'CNY', 'PENDING')
                """, orderId, ownerId);
        TransactionTimelineWriter.order(jdbcTemplate, ownerId, orderId, "ORDER_CREATED",
                orderCreatedAt, "timeline-order-created", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                "", "ORDER_DURABLY_CREATED");
        jdbcTemplate.update("""
                INSERT INTO payment_order (
                    payment_no, order_id, provider, amount, currency, status
                ) VALUES ('MOCK_11111111111111111111111111111111', ?, 'MOCK',
                          19.90, 'CNY', 'PENDING')
                """, orderId);
        TransactionTimelineWriter.order(jdbcTemplate, ownerId, orderId, "PENDING_PAYMENT",
                paymentCreatedAt, "timeline-payment-created", "", "", "AWAITING_MOCK_PAYMENT");

        timelineService.requireOwnedOrder(orderId, ownerId);
        List<TransactionTimelineEventResponse> initial = timelineService.durableOrderEvents(
                orderId, ownerId, 0
        );
        assertThat(initial).extracting(TransactionTimelineEventResponse::eventType)
                .containsExactly("ORDER_CREATED", "PENDING_PAYMENT");
        long lastEventId = initial.get(initial.size() - 1).eventId();

        jdbcTemplate.update("UPDATE sales_order SET status = 'PAID', paid_at = ? WHERE order_id = ?",
                Timestamp.from(paidAt), orderId);
        jdbcTemplate.update("""
                UPDATE payment_order
                   SET provider_transaction_no = 'txn-timeline-1', status = 'SUCCEEDED', paid_at = ?
                 WHERE order_id = ?
                """, Timestamp.from(paidAt), orderId);

        TransactionTimelineService recreated = new TransactionTimelineService(
                jdbcTemplate, reservationStatusService
        );
        int rowsBeforeRead = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_transaction_timeline WHERE order_id = ?",
                Integer.class,
                orderId
        );
        assertThat(recreated.orderEvents(orderId, ownerId, 0))
                .extracting(TransactionTimelineEventResponse::eventType)
                .containsExactly("ORDER_CREATED", "PENDING_PAYMENT");
        assertThat(recreated.durableOrderEvents(orderId, ownerId, lastEventId))
                .as("the SSE loader must read committed receipts without synthesizing PAID")
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_transaction_timeline WHERE order_id = ?",
                Integer.class,
                orderId
        )).isEqualTo(rowsBeforeRead);

        TransactionTimelineWriter.order(jdbcTemplate, ownerId, orderId, "PAID", paidAt,
                "timeline-payment-callback", "", "", "MOCK_PAYMENT_CONFIRMED");

        recreated.requireOwnedOrder(orderId, ownerId);
        List<TransactionTimelineEventResponse> recovered = recreated.durableOrderEvents(
                orderId, ownerId, lastEventId
        );
        assertThat(recovered).extracting(TransactionTimelineEventResponse::eventType)
                .containsExactly("PAID");
        assertThat(recovered.get(0).eventId()).isGreaterThan(lastEventId);
        assertThat(recreated.durableOrderEvents(orderId, ownerId, 0))
                .extracting(TransactionTimelineEventResponse::eventType)
                .containsExactly("ORDER_CREATED", "PENDING_PAYMENT", "PAID");
        assertThat(recovered.get(0).occurredAt()).isEqualTo(paidAt);
        assertThat(recovered.get(0).requestId()).isEqualTo("timeline-payment-callback");
        org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                () -> recreated.requireOwnedOrder(orderId, ownerId + 1)
        );
    }

    @Test
    @Order(11)
    void ordinaryOrderHttpContractDistinguishesCreateReplayAndConflict() throws Exception {
        CustomUserDetails principal = CustomUserDetails.builder()
                .userId(811L)
                .username("ordinary-contract-user")
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        String key = "ordinary-contract-key-00000000000000000001";
        String traceparent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
        String tracestate = "hotshop=ordinary-contract";
        String body = "{\"items\":[{\"productId\":\"" + PRODUCT_ID
                + "\",\"quantity\":1}]}";

        String first = mockMvc.perform(post("/api/v1/orders")
                        .with(user(principal))
                        .header("X-Request-ID", "ordinary-original-request")
                        .header("traceparent", traceparent)
                        .header("tracestate", tracestate)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.requestId").value("ordinary-original-request"))
                .andExpect(jsonPath("$.idempotencyReplayed").value(false))
                .andReturn().getResponse().getContentAsString();
        String orderId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first).path("orderId").asText();
        assertThat(orderId).isNotBlank();

        mockMvc.perform(post("/api/v1/orders")
                        .with(user(principal))
                        .header("X-Request-ID", "ordinary-retry-request")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.requestId").value("ordinary-original-request"))
                .andExpect(jsonPath("$.idempotencyReplayed").value(true));

        mockMvc.perform(post("/api/v1/orders")
                        .with(user(principal))
                        .header("X-Request-ID", "ordinary-conflict-request")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"" + PRODUCT_ID
                                + "\",\"quantity\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sales_order WHERE user_id = ?",
                Integer.class,
                811L
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_transaction_timeline WHERE order_id = ? "
                        + "AND event_type = 'ORDER_CREATED'",
                Integer.class,
                orderId
        )).isOne();
        java.util.Map<String, Object> timelineTrace = jdbcTemplate.queryForMap(
                "SELECT request_id, traceparent, tracestate "
                        + "FROM user_transaction_timeline WHERE resource_type = 'ORDER' "
                        + "AND order_id = ? AND event_type = 'ORDER_CREATED'",
                orderId
        );
        assertThat(timelineTrace).containsEntry("request_id", "ordinary-original-request")
                .containsEntry("tracestate", tracestate);
        assertThat(timelineTrace.get("traceparent").toString())
                .startsWith("00-1234567890abcdef1234567890abcdef-")
                .endsWith("-01");
        assertThat(jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.tracestate'))
                  FROM outbox_event
                 WHERE aggregate_id = ?
                   AND event_type IN ('ORDER_CREATED', 'LEGACY_ORDER_TIMEOUT_REQUESTED')
                 ORDER BY event_type
                """, String.class, orderId)).containsExactly(tracestate, tracestate);
    }

    private List<FlashSaleReservationResult> invoke(
            List<Callable<FlashSaleReservationResult>> calls
    ) throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            return executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }
    }

    private void destroyConnectionFactory(StringRedisTemplate template) {
        ((LettuceConnectionFactory) template.getConnectionFactory()).destroy();
    }

    private void insertProduct() {
        jdbcTemplate.update(
                """
                INSERT INTO catalog_product (
                    product_id, sku, name, price, stock, category, status, version
                ) VALUES (?, ?, ?, 99.00, 1000, 'TASK07', 'ACTIVE', 1)
                """,
                PRODUCT_ID,
                "TASK07-" + PRODUCT_ID,
                "TASK-07 Product"
        );
    }

    private void insertActivity(
            long activityId,
            int total,
            int available,
            int limit,
            String status,
            long startOffsetSeconds,
            long endOffsetSeconds,
            int version
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO flash_sale_activity (
                    activity_id, activity_code, product_id, sale_price,
                    total_stock, available_stock, per_user_limit, status,
                    starts_at, ends_at, version
                ) VALUES (?, ?, ?, 19.90, ?, ?, ?, ?, ?, ?, ?)
                """,
                activityId,
                "TASK07-" + activityId,
                PRODUCT_ID,
                total,
                available,
                limit,
                status,
                Timestamp.from(Instant.now().plusSeconds(startOffsetSeconds)),
                Timestamp.from(Instant.now().plusSeconds(endOffsetSeconds)),
                version
        );
    }

    private void resetSeckillAndReload(long activityId) {
        resetSeckill();
        assertThat(loader.load(activityId).code()).isEqualTo(FlashSaleLoadCode.LOADED);
    }

    private void resetSeckill() {
        seckillRedis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private long streamLength(long activityId) {
        Long size = seckillRedis.opsForStream().size(SeckillRedisKeys.reservationStream(activityId));
        return size == null ? 0 : size;
    }

    private long reservationRecordCount(long activityId) {
        List<MapRecord<String, Object, Object>> events = seckillRedis.opsForStream().range(
                SeckillRedisKeys.reservationStream(activityId),
                org.springframework.data.domain.Range.unbounded()
        );
        if (events == null) {
            return 0;
        }
        return events.stream()
                .map(event -> String.valueOf(event.getValue().get("reservationNo")))
                .filter(reservationNo -> Boolean.TRUE.equals(seckillRedis.hasKey(
                        SeckillRedisKeys.reservation(activityId, reservationNo)
                )))
                .count();
    }

    private int stock(long activityId) {
        return Integer.parseInt(seckillRedis.opsForValue().get(
                SeckillRedisKeys.availableStock(activityId)
        ));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

}
