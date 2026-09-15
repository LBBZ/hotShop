package com.real.task.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.infrastructure.redis.SeckillRedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(SeckillOrderReliabilityContainerTest.TestConfiguration.class)
class SeckillOrderReliabilityContainerTest {
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0.46"))
                    .withDatabaseName("hotshop")
                    .withUsername("hotshop")
                    .withPassword("hotshop")
                    .withCommand("--log-bin-trust-function-creators=1");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.1-alpine"))
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @jakarta.annotation.Resource(name = "seckillStringRedisTemplate")
    private StringRedisTemplate redis;

    @jakarta.annotation.Resource
    private ReservationStreamConsumer consumer;

    @jakarta.annotation.Resource
    private SeckillProcessingService processingService;

    @jakarta.annotation.Resource
    private SeckillRedisReservationGateway reservationGateway;

    @jakarta.annotation.Resource
    private SeckillReconciliationService reconciliationService;

    @jakarta.annotation.Resource
    private SeckillOrderProperties properties;

    @jakarta.annotation.Resource
    private MutableFailpoint failpoint;

    @jakarta.annotation.Resource
    private SeckillOrderMetrics metrics;

    @BeforeEach
    void resetPersistentState() {
        failpoint.clear();
        properties.setReconciliationDryRun(true);
        properties.setReconciliationBatch(200);
        properties.setAutoRepair(false);
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            for (String table : List.of(
                    "seckill_reconciliation_checkpoint",
                    "seckill_reconciliation_issue",
                    "seckill_event_processing",
                    "processed_event",
                    "user_transaction_timeline",
                    "outbox_event",
                    "sales_order_item",
                    "sales_order",
                    "sale_reservation",
                    "flash_sale_activity",
                    "catalog_product",
                    "payment_order",
                    "refresh_token",
                    "audit_log",
                    "app_user"
            )) {
                jdbc.execute("TRUNCATE TABLE " + table);
            }
        });
    }

    @Test
    void discoversAndConsumesEveryRegisteredActivityStream() {
        seedActivity(11, 111, 1, 1);
        seedActivity(12, 112, 1, 1);
        Accepted first = accepted(1, 11, 111, 1001, 1, "7.50");
        Accepted second = accepted(2, 12, 112, 1002, 1, "9.25");
        appendAccepted(first);
        appendAccepted(second);

        consumer.refreshStreams();
        assertThat(consumer.discoveredStreams()).containsExactly(
                SeckillRedisKeys.reservationStream(11),
                SeckillRedisKeys.reservationStream(12)
        );

        pollUntilOrders(2);

        assertThat(count("sales_order")).isEqualTo(2);
        assertThat(redis.opsForHash().get(first.reservationKey(), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(redis.opsForHash().get(second.reservationKey(), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(pending(first.stream())).isZero();
        assertThat(pending(second.stream())).isZero();
        assertThat(countWhere("user_transaction_timeline",
                "resource_type = 'RESERVATION' AND event_type = 'RESERVED'")).isEqualTo(2);
        assertThat(countWhere("user_transaction_timeline",
                "resource_type = 'RESERVATION' AND event_type = 'ORDER_CREATED'")).isEqualTo(2);
        assertThat(countWhere("outbox_event",
                "JSON_UNQUOTE(JSON_EXTRACT(payload, '$.tracestate')) = 'hotshop=seckill-test'"))
                .isEqualTo(4);
    }

    @Test
    void emptyCarrierWithBrokenTracerCannotLeakWorkerTraceIntoTimelineOrOutbox() {
        seedActivity(13, 113, 1, 1);
        Accepted event = accepted(13_001, 13, 113, 13_101, 1, "8.50");
        Map<String, String> fields = new LinkedHashMap<>(event.fields());
        fields.put("traceparent", "");
        fields.put("tracestate", "");
        Map<String, String> reservationHash = new LinkedHashMap<>(fields);
        reservationHash.remove("eventType");
        reservationHash.remove("eventId");
        reservationHash.remove("occurredAtMs");
        redis.opsForHash().putAll(event.reservationKey(), reservationHash);
        redis.opsForValue().set(event.userKey(), event.reservationNo());
        redis.opsForValue().decrement(
                SeckillRedisKeys.availableStock(event.activityId()), event.quantity());
        redis.opsForSet().add(SeckillRedisKeys.reservationStreamRegistry(), event.stream());
        redis.opsForZSet().add(SeckillRedisKeys.reconciliationStreamIndex(), event.stream(), 0);
        redis.opsForHash().increment(SeckillRedisKeys.activityMetadata(event.activityId()), "inventoryRevision", 1);
        appendRaw(event.stream(), fields);

        Tracer brokenTracer = mock(Tracer.class);
        when(brokenTracer.spanBuilder()).thenThrow(
                new IllegalStateException("consumer tracer unavailable"));
        ReservationStreamConsumer isolatedConsumer = new ReservationStreamConsumer(
                redis, properties, reservationGateway, processingService, failpoint, metrics,
                brokenTracer);
        Map<String, String> ambient = Map.of(
                "requestId", "unrelated-request",
                "traceId", "a".repeat(32),
                "spanId", "b".repeat(16),
                "tracestate", "unrelated=value");
        MDC.setContextMap(ambient);
        try {
            isolatedConsumer.refreshStreams();
            isolatedConsumer.poll();
            assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(ambient);
        } finally {
            MDC.clear();
        }

        assertThat(count("sales_order")).isOne();
        assertThat(countWhere("user_transaction_timeline",
                "COALESCE(traceparent,'') <> '' OR COALESCE(tracestate,'') <> ''")).isZero();
        assertThat(countWhere("outbox_event",
                "JSON_UNQUOTE(JSON_EXTRACT(payload,'$.traceparent')) <> ''"
                        + " OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.tracestate')) <> ''"))
                .isZero();
        assertThat(count("outbox_event")).isEqualTo(2);
    }

    @Test
    void convertsOneHundredReservationsAndConservesMysqlAndRedisStock() {
        seedActivity(21, 121, 100, 100);
        List<Accepted> accepted = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            Accepted event = accepted(1_000 + index, 21, 121, 2_000 + index, 1, "3.25");
            accepted.add(event);
            appendAccepted(event);
        }

        consumer.refreshStreams();
        pollUntilOrders(100);

        assertThat(count("sales_order")).isEqualTo(100);
        assertThat(count("sales_order_item")).isEqualTo(100);
        assertThat(count("outbox_event")).isEqualTo(200);
        assertThat(countWhere("outbox_event", "event_type = 'ORDER_CREATED'")).isEqualTo(100);
        assertThat(countWhere("outbox_event", "event_type = 'LEGACY_ORDER_TIMEOUT_REQUESTED'")).isEqualTo(100);
        assertThat(countWhere("seckill_event_processing", "status = 'ORDER_CREATED'"))
                .isEqualTo(100);
        assertThat(integer("SELECT available_stock FROM flash_sale_activity WHERE activity_id = 21"))
                .isZero();
        assertThat(integer("SELECT stock FROM catalog_product WHERE product_id = 121"))
                .isZero();
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(21))).isEqualTo("0");
        assertThat(accepted)
                .allSatisfy(event -> assertThat(
                        redis.opsForHash().get(event.reservationKey(), "status")
                ).isEqualTo("ORDER_CREATED"));
        assertThat(pending(accepted.get(0).stream())).isZero();
    }

    @Test
    void duplicateDeliveryAndDifferentEventForSameReservationHaveOneBusinessEffect() {
        seedActivity(31, 131, 5, 5);
        Accepted first = accepted(3_001, 31, 131, 3_101, 1, "4.00");
        appendAccepted(first);
        appendRaw(first.stream(), first.fields());
        Accepted secondEvent = first.withEventId(eventId(3_002));
        appendRaw(first.stream(), secondEvent.fields());

        consumer.refreshStreams();
        pollUntilPendingEmpty(first.stream());

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(count("sales_order_item")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(count("seckill_event_processing")).isEqualTo(2);
        assertThat(integer("SELECT available_stock FROM flash_sale_activity WHERE activity_id = 31"))
                .isEqualTo(4);
        assertThat(integer("SELECT stock FROM catalog_product WHERE product_id = 131"))
                .isEqualTo(4);
    }

    @Test
    void failureBeforeCommitRollsBackAndPendingRetryCreatesExactlyOneOrderAndOutbox() {
        seedActivity(41, 141, 1, 1);
        Accepted event = accepted(4_001, 41, 141, 4_101, 1, "5.00");
        appendAccepted(event);
        failpoint.failOnceAt(FailurePoint.BEFORE_ORDER_COMMIT);

        consumer.refreshStreams();
        consumer.poll();

        assertThat(count("sales_order")).isZero();
        assertThat(count("sales_order_item")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(integer("SELECT available_stock FROM flash_sale_activity WHERE activity_id = 41"))
                .isEqualTo(1);
        assertThat(pending(event.stream())).isEqualTo(1);
        assertThat(text("SELECT status FROM seckill_event_processing WHERE event_id = ?",
                event.eventId())).isEqualTo("RETRYING");

        awaitRetryAndClaim(event.stream());

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void commitBeforeFinalizeFailureIsClaimedWithoutSecondOrderOrStockDeduction() {
        seedActivity(51, 151, 2, 2);
        Accepted event = accepted(5_001, 51, 151, 5_101, 1, "6.00");
        appendAccepted(event);
        failpoint.failOnceAt(FailurePoint.AFTER_ORDER_COMMIT);

        consumer.refreshStreams();
        consumer.poll();

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(redis.opsForHash().get(event.reservationKey(), "status")).isEqualTo("RESERVED");
        assertThat(pending(event.stream())).isEqualTo(1);

        awaitRetryAndClaim(event.stream());

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(count("sales_order_item")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(integer("SELECT available_stock FROM flash_sale_activity WHERE activity_id = 51"))
                .isEqualTo(1);
        assertThat(redis.opsForHash().get(event.reservationKey(), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void finalizeBeforeAckFailureReplaysIdempotentlyAndClearsPending() {
        seedActivity(61, 161, 2, 2);
        Accepted event = accepted(6_001, 61, 161, 6_101, 1, "8.00");
        appendAccepted(event);
        failpoint.failOnceAt(FailurePoint.AFTER_FINALIZE);

        consumer.refreshStreams();
        consumer.poll();

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(redis.opsForHash().get(event.reservationKey(), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(pending(event.stream())).isEqualTo(1);

        awaitRetryAndClaim(event.stream());

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(integer("SELECT stock FROM catalog_product WHERE product_id = 161"))
                .isEqualTo(1);
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void newConsumerUsesXautoclaimToRecoverOldConsumerPendingEntry() {
        seedActivity(71, 171, 1, 1);
        Accepted event = accepted(7_001, 71, 171, 7_101, 1, "9.00");
        appendAccepted(event);
        consumer.refreshStreams();

        List<MapRecord<String, Object, Object>> delivered = redis.opsForStream().read(
                Consumer.from(properties.getGroupName(), "dead-consumer"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(event.stream(), ReadOffset.lastConsumed())
        );
        assertThat(delivered).hasSize(1);
        assertThat(pending(event.stream())).isEqualTo(1);

        awaitClaimablePending(event.stream());
        ReservationStreamConsumer replacement = new ReservationStreamConsumer(
                redis,
                properties,
                reservationGateway,
                processingService,
                failpoint,
                metrics
        );
        replacement.refreshStreams();
        replacement.poll();

        assertThat(replacement.consumerName()).isNotEqualTo(consumer.consumerName());
        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void mysqlOutageKeepsPendingWithoutCompensationAndRecovers() {
        seedActivity(73, 173, 1, 1);
        Accepted event = accepted(7_003, 73, 173, 7_103, 1, "8.00");
        appendAccepted(event);
        consumer.refreshStreams();

        pause(MYSQL.getContainerId());
        try {
            consumer.poll();
        } finally {
            unpause(MYSQL.getContainerId());
        }

        assertThat(count("sales_order")).isZero();
        assertThat(redis.opsForHash().get(event.reservationKey(), "status"))
                .isEqualTo("RESERVED");
        assertThat(pending(event.stream())).isEqualTo(1);

        awaitRetryAndClaim(event.stream());

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(countWhere("seckill_event_processing", "status = 'COMPENSATED'"))
                .isZero();
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void redisOutageAfterMysqlCommitDoesNotCreateSecondOrderOrCompensate() {
        seedActivity(74, 174, 2, 2);
        Accepted event = accepted(7_004, 74, 174, 7_104, 1, "8.50");
        appendAccepted(event);
        failpoint.failOnceAt(FailurePoint.AFTER_ORDER_COMMIT);
        consumer.refreshStreams();
        consumer.poll();

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(pending(event.stream())).isEqualTo(1);

        pause(REDIS.getContainerId());
        try {
            consumer.poll();
        } finally {
            unpause(REDIS.getContainerId());
        }

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(countWhere("seckill_event_processing", "status = 'COMPENSATED'"))
                .isZero();
        assertThat(integer(
                "SELECT available_stock FROM flash_sale_activity WHERE activity_id = 74"
        )).isEqualTo(1);

        awaitRetryAndClaim(event.stream());

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(redis.opsForHash().get(event.reservationKey(), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void compensationCrashRecoveryRestoresStockExactlyOnceAndWritesAuditAndOutbox() {
        seedActivity(81, 181, 0, 1);
        Accepted event = accepted(8_001, 81, 181, 8_101, 1, "10.00");
        appendAccepted(event);
        failpoint.failOnceAt(FailurePoint.AFTER_REDIS_COMPENSATION);

        consumer.refreshStreams();
        consumer.poll();

        assertThat(count("sales_order")).isZero();
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(81))).isEqualTo("1");
        assertThat(redis.opsForValue().get(event.userKey())).isNull();
        assertThat(redis.opsForHash().get(event.reservationKey(), "status"))
                .isEqualTo("COMPENSATED");
        assertThat(text("SELECT status FROM sale_reservation WHERE reservation_no = ?",
                event.reservationNo())).isEqualTo("COMPENSATING");
        assertThat(pending(event.stream())).isEqualTo(1);

        awaitRetryAndClaim(event.stream());
        appendRaw(event.stream(), event.fields());
        pollUntilPendingEmpty(event.stream());

        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(81))).isEqualTo("1");
        assertThat(text("SELECT status FROM sale_reservation WHERE reservation_no = ?",
                event.reservationNo())).isEqualTo("COMPENSATED");
        assertThat(countWhere("outbox_event", "event_type = 'RESERVATION_COMPENSATED'"))
                .isEqualTo(1);
        assertThat(countWhere("audit_log", "action = 'RESERVATION_COMPENSATED'")).isEqualTo(1);
        assertThat(countWhere("user_transaction_timeline", "event_type = 'RESERVED'")).isEqualTo(1);
        assertThat(countWhere("user_transaction_timeline", "event_type = 'COMPENSATING'")).isEqualTo(1);
        assertThat(countWhere("user_transaction_timeline", "event_type = 'COMPENSATED'")).isEqualTo(1);
        assertThat(count("sales_order")).isZero();
        assertThat(pending(event.stream())).isZero();
    }

    @Test
    void compensatedLedgerWithConflictingOrderIsPersistedForManualReviewBeforeAck() {
        seedActivity(82, 182, 0, 1);
        Accepted event = accepted(8_002, 82, 182, 8_102, 1, "10.50");
        appendAccepted(event);

        consumer.refreshStreams();
        pollUntilPendingEmpty(event.stream());
        assertThat(text("SELECT status FROM seckill_event_processing WHERE event_id = ?",
                event.eventId())).isEqualTo("COMPENSATED");

        Long reservationId = jdbc.queryForObject(
                "SELECT reservation_id FROM sale_reservation WHERE reservation_no = ?",
                Long.class,
                event.reservationNo()
        );
        jdbc.update("""
                INSERT INTO sales_order (
                    order_id, user_id, reservation_id, total_amount, currency, status
                ) VALUES (?, ?, ?, ?, 'CNY', 'PENDING')
                """, "ord_injected_compensation_conflict", event.userId(), reservationId,
                new BigDecimal(event.unitPrice()).multiply(BigDecimal.valueOf(event.quantity())));
        appendRaw(event.stream(), event.fields());

        pollUntilPendingEmpty(event.stream());

        assertThat(text("SELECT status FROM seckill_event_processing WHERE event_id = ?",
                event.eventId())).isEqualTo("MANUAL_REVIEW");
        assertThat(text("SELECT reason_code FROM seckill_event_processing WHERE event_id = ?",
                event.eventId())).isEqualTo("ORDER_EXISTS_COMPENSATION_FORBIDDEN");
        assertThat(countWhere(
                "seckill_reconciliation_issue",
                "issue_type = 'ORDER_EXISTS_COMPENSATION_FORBIDDEN' AND status = 'OPEN'"
        )).isEqualTo(1);
        assertThat(pending(event.stream())).isZero();
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(82))).isEqualTo("1");
    }

    @Test
    void poisonEventIsPersistedForManualHandlingBeforeAckWithoutCompensation() {
        long activityId = 91;
        String stream = SeckillRedisKeys.reservationStream(activityId);
        redis.opsForSet().add(SeckillRedisKeys.reservationStreamRegistry(), stream);
        appendRaw(stream, Map.of(
                "schemaVersion", "2",
                "eventType", "RESERVATION_ACCEPTED",
                "eventId", eventId(9_001)
        ));

        consumer.refreshStreams();
        consumer.poll();

        assertThat(text("SELECT status FROM seckill_event_processing LIMIT 1"))
                .isEqualTo("QUARANTINED");
        assertThat(countWhere("seckill_reconciliation_issue", "status = 'OPEN'")).isEqualTo(1);
        assertThat(count("sales_order")).isZero();
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(activityId))).isNull();
        assertThat(pending(stream)).isZero();
        assertThat(redis.opsForStream().size(stream)).isEqualTo(1);
    }

    @Test
    void reconciliationDryRunFindsCorruptionWithoutChangingBusinessFacts() {
        seedActivity(101, 201, 2, 2);
        Accepted event = accepted(10_001, 101, 201, 10_101, 1, "11.00");
        appendAccepted(event);
        consumer.refreshStreams();
        pollUntilOrders(1);

        redis.opsForValue().set(SeckillRedisKeys.availableStock(101), "99");
        BusinessSnapshot before = snapshot(event);

        SeckillReconciliationService.ReconciliationReport report =
                reconciliationService.runBatch();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.autoRepair()).isFalse();
        assertThat(report.findings()).isGreaterThan(0);
        assertThat(report.repairs()).isZero();
        assertThat(snapshot(event)).isEqualTo(before);
        assertThat(countWhere("seckill_reconciliation_issue", "status = 'OPEN'"))
                .isGreaterThan(0);
    }

    @Test
    void repairModeOnlyFinalizesCommittedOrderAndAcksTerminalPendingWithoutFixingStockDelta() {
        seedActivity(111, 211, 2, 2);
        Accepted event = accepted(11_001, 111, 211, 11_101, 1, "12.00");
        appendAccepted(event);
        failpoint.failOnceAt(FailurePoint.AFTER_ORDER_COMMIT);
        consumer.refreshStreams();
        consumer.poll();

        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(redis.opsForHash().get(event.reservationKey(), "status")).isEqualTo("RESERVED");
        assertThat(pending(event.stream())).isEqualTo(1);
        redis.opsForValue().set(SeckillRedisKeys.availableStock(111), "77");
        properties.setReconciliationDryRun(false);
        properties.setAutoRepair(true);

        SeckillReconciliationService.ReconciliationReport report =
                reconciliationService.runBatch();

        assertThat(report.repairs()).isGreaterThanOrEqualTo(2);
        assertThat(redis.opsForHash().get(event.reservationKey(), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(pending(event.stream())).isZero();
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(111))).isEqualTo("77");
        assertThat(count("sales_order")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
    }

    @Test
    void review03OrdinaryPurchaseAfterActivityLoadDoesNotRaiseConservationIssue() throws Exception {
        seedActivity(121, 221, 100, 100);
        redis.opsForSet().add(SeckillRedisKeys.reservationStreamRegistry(), SeckillRedisKeys.reservationStream(121));
        redis.opsForZSet().add(SeckillRedisKeys.reconciliationStreamIndex(), SeckillRedisKeys.reservationStream(121), 0);
        consumer.refreshStreams();
        assertThat(productMapper().reduceStock(221L, 1)).isEqualTo(1);

        reconciliationService.runBatch();

        assertThat(jdbc.queryForList("SELECT * FROM seckill_reconciliation_issue WHERE issue_type = 'MYSQL_STOCK_CONSERVATION_VIOLATION'"))
                .as("Ordinary purchase must be inside product inventory conservation boundary").isEmpty();
    }

    @Test
    void review04HistoricalReservationsRespectActualRedisReadBudget() {
        seedActivity(122, 222, 100, 100);
        for (int i = 0; i < 37; i++) {
            appendAccepted(accepted(12000 + i, 122, 222, 22000 + i, 1, "1.00"));
        }
        consumer.refreshStreams();
        properties.setReconciliationBatch(3);
        long before = redisCommandCalls("hgetall");
        var connection = redis.getConnectionFactory().getConnection();
        connection.serverCommands().setConfig("slowlog-log-slower-than", "0");
        var nativeCommands = (io.lettuce.core.api.async.RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
        nativeCommands.slowlogReset().toCompletableFuture().join();

        reconciliationService.runBatch();

        var log = nativeCommands.slowlogGet(128).toCompletableFuture().join();
        List<List<String>> ranges = new ArrayList<>();
        for (Object item : log) {
            var entry = (List<?>) item;
            var command = (List<?>) entry.get(3);
            List<String> args = command.stream().map(value -> new String((byte[]) value, StandardCharsets.UTF_8)).toList();
            if (args.getFirst().equalsIgnoreCase("XRANGE")) ranges.add(args);
        }
        connection.serverCommands().setConfig("slowlog-log-slower-than", "10000");
        assertThat(ranges).isNotEmpty();
        for (List<String> args : ranges) {
            int count = -1;
            for (int i = 0; i < args.size(); i++) if (args.get(i).equalsIgnoreCase("COUNT")) count = i;
            assertThat(count).as("Every actual XRANGE, including Lua, must carry COUNT: %s", args).isGreaterThan(0);
            assertThat(Integer.parseInt(args.get(count + 1))).isLessThanOrEqualTo(3);
        }
        assertThat(redisCommandCalls("hgetall") - before)
                .as("Actual reservation reads must be bounded by the configured page, independently of history")
                .isLessThanOrEqualTo(16);
    }

    @Test
    void review04PagesResumeAfterServiceRestartAndRotateAcrossActivities() {
        seedActivity(123, 223, 100, 100);
        seedActivity(124, 224, 100, 100);
        for (int i = 0; i < 37; i++) {
            appendAccepted(accepted(13000 + i, 123, 223, 23000 + i, 1, "1.00"));
            appendAccepted(accepted(14000 + i, 124, 224, 24000 + i, 1, "1.00"));
        }
        consumer.refreshStreams();
        properties.setReconciliationBatch(3);
        reconciliationService.runBatch();
        reconciliationService.runBatch();
        assertThat(redis.opsForHash().get(SeckillRedisKeys.conservationCheckpoint(123), "state"))
                .isEqualTo("IN_PROGRESS");
        assertThat(redis.opsForHash().get(SeckillRedisKeys.conservationCheckpoint(124), "state"))
                .isEqualTo("IN_PROGRESS");
        String persistedCursor = String.valueOf(redis.opsForHash().get(
                SeckillRedisKeys.conservationCheckpoint(123), "cursor"));
        var restarted = new SeckillReconciliationService(redis, jdbc, properties,
                reservationGateway, processingService, metrics);
        for (int i = 0; i < 24; i++) {
            long hashes = redisCommandCalls("hgetall");
            long fields = redisCommandCalls("hmget");
            restarted.runBatch();
            assertThat(redisCommandCalls("hgetall") - hashes).isLessThanOrEqualTo(16);
            assertThat(redisCommandCalls("hmget") - fields).isLessThanOrEqualTo(3);
        }
        assertThat(persistedCursor).isNotEqualTo("0-0");
        for (long activity : List.of(123L, 124L)) {
            String key = SeckillRedisKeys.conservationCheckpoint(activity);
            assertThat(redis.opsForHash().get(key, "completedScans")).isEqualTo("1");
            assertThat(redis.opsForHash().get(key, "lastCompletedQuantity")).isEqualTo("37");
        }
        assertThat(jdbc.queryForList("SELECT * FROM seckill_reconciliation_issue")).isEmpty();
    }

    @Test
    void review04CompensationBetweenPagesRestartsSnapshotAndStillFindsRealTampering() {
        seedActivity(125, 225, 100, 100);
        Accepted first = accepted(15000, 125, 225, 25000, 1, "1.00");
        appendAccepted(first);
        for (int i = 1; i < 8; i++) appendAccepted(accepted(15000 + i, 125, 225, 25000 + i, 1, "1.00"));
        consumer.refreshStreams();
        properties.setReconciliationBatch(3);
        reconciliationService.runBatch();
        var parsed = ReservationAcceptedEvent.parse(first.stream(), "1-0", new LinkedHashMap<Object, Object>(first.fields()));
        assertThat(parsed.valid()).isTrue();
        assertThat(reservationGateway.compensate(parsed.event(), "review-compensation", "INVENTORY_SHORTAGE").successful()).isTrue();
        // A new reservation by the same user legally owns the restored user slot.
        appendAccepted(accepted(16000, 125, 225, 25000, 1, "1.00"));
        for (int i = 0; i < 4; i++) reconciliationService.runBatch();
        assertThat(redis.opsForHash().get(SeckillRedisKeys.conservationCheckpoint(125), "restarts"))
                .isEqualTo("1");
        assertThat(redis.opsForHash().get(SeckillRedisKeys.conservationCheckpoint(125), "lastCompletedQuantity"))
                .isEqualTo("8");
        assertThat(jdbc.queryForList("SELECT * FROM seckill_reconciliation_issue")).isEmpty();

        redis.opsForValue().increment(SeckillRedisKeys.availableStock(125));
        for (int i = 0; i < 4; i++) reconciliationService.runBatch();
        var issues = jdbc.queryForList("SELECT severity, evidence_summary FROM seckill_reconciliation_issue WHERE issue_type='REDIS_STOCK_CONSERVATION_VIOLATION'");
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().get("severity")).isEqualTo("CRITICAL");
        assertThat(issues.getFirst().get("evidence_summary").toString()).contains("\"currentStock\": 93", "\"effectiveReservedQuantity\": 8");
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(125))).isEqualTo("93");
    }

    @Test
    void review04LegacyRegistryUpgradeIsExplicitResumableAndPreservesOrphanStreams() {
        // More than Redis' compact-set threshold; the upgrade returns its actual
        // work count and stores its SSCAN cursor, independent of runBatch.
        for (long i = 300; i < 460; i++) redis.opsForSet().add(
                SeckillRedisKeys.reservationStreamRegistry(), SeckillRedisKeys.reservationStream(i));
        reconciliationService.runBatch();
        assertThat(jdbc.queryForList("SELECT * FROM seckill_reconciliation_issue WHERE issue_type='RECONCILIATION_INDEX_UPGRADE_REQUIRED'")).hasSize(1);
        assertThat(redis.opsForZSet().zCard(SeckillRedisKeys.reconciliationStreamIndex())).isZero();
        var upgrade = new org.springframework.data.redis.core.script.DefaultRedisScript<List>();
        upgrade.setLocation(new org.springframework.core.io.ClassPathResource("redis/upgrade-reconciliation-index-v1.lua"));
        upgrade.setResultType(List.class);
        String checkpoint = SeckillRedisKeys.reconciliationStreamIndex() + ":upgrade";
        List<?> result;
        int steps = 0;
        do {
            result = redis.execute(upgrade, List.of(SeckillRedisKeys.reservationStreamRegistry(),
                    SeckillRedisKeys.reconciliationStreamIndex(), checkpoint), "3");
            assertThat(result).hasSize(3);
            assertThat(redis.opsForHash().get(checkpoint, "cursor")).isEqualTo(result.getFirst().toString());
            steps++;
        } while (!"0".equals(result.getFirst().toString()) && steps < 200);
        assertThat(steps).isBetween(2, 199);
        assertThat(redis.opsForZSet().zCard(SeckillRedisKeys.reconciliationStreamIndex())).isEqualTo(160);
        assertThat(redis.opsForSet().size(SeckillRedisKeys.reservationStreamRegistry())).isEqualTo(160);
        reconciliationService.runBatch();
        assertThat(jdbc.queryForList("SELECT * FROM seckill_reconciliation_issue WHERE issue_type='REDIS_ACTIVITY_FACT_INVALID' AND activity_id=300")).hasSize(1);
    }

    @Test
    void review04ReverseEvidenceContinuesPastTwoInvalidCandidatesWithoutFalseIssue() {
        seedActivity(126, 226, 100, 100);
        Accepted accepted = accepted(17000, 126, 226, 27000, 1, "1.00");
        appendAccepted(accepted);
        for (int i = 1; i <= 2; i++) {
            jdbc.update("""
                    INSERT INTO seckill_event_processing(event_id,stream_key,stream_entry_id,
                        reservation_no,activity_id,payload_hash,status)
                    VALUES(?,?,?,?,?,?,'MANUAL_REVIEW')
                    """, "review-fake-" + i, accepted.stream(), i + "-0", accepted.reservationNo(), 126, "0".repeat(64));
        }
        consumer.refreshStreams();
        pollUntilOrders(1);
        for (int i = 0; i < 5; i++) reconciliationService.runBatch();
        assertThat(jdbc.queryForList("SELECT * FROM seckill_reconciliation_issue WHERE issue_type='MYSQL_RESERVATION_WITHOUT_STREAM_EVIDENCE'")).isEmpty();
        long reservationId = jdbc.queryForObject("SELECT reservation_id FROM sale_reservation WHERE reservation_no=?", Long.class, accepted.reservationNo());
        assertThat(jdbc.queryForObject("SELECT cursor_value FROM seckill_reconciliation_checkpoint WHERE checkpoint_name=?", String.class,
                "stream-evidence-" + reservationId)).isEqualTo("0");
    }

    private long redisCommandCalls(String command) {
        String stats = redis.getConnectionFactory().getConnection().serverCommands()
                .info("commandstats").getProperty("cmdstat_" + command, "calls=0");
        return Long.parseLong(stats.substring(6).split(",")[0]);
    }

    private com.real.domain.mapper.ProductMapper productMapper() throws Exception {
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setTypeAliasesPackage("com.real.domain.entity");
        factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/real/domain/mapper/*.xml"));
        return new org.mybatis.spring.SqlSessionTemplate(factory.getObject())
                .getMapper(com.real.domain.mapper.ProductMapper.class);
    }

    private void seedActivity(
            long activityId,
            long productId,
            int mysqlStock,
            int initialStock
    ) {
        jdbc.update("""
                INSERT INTO catalog_product (
                    product_id, sku, name, price, stock, status
                ) VALUES (?, ?, ?, 99.00, ?, 'ACTIVE')
                """, productId, "SKU-" + productId, "Product " + productId, mysqlStock);
        jdbc.update("""
                INSERT INTO flash_sale_activity (
                    activity_id, activity_code, product_id, sale_price,
                    total_stock, available_stock, per_user_limit, status,
                    starts_at, ends_at
                ) VALUES (?, ?, ?, 1.00, ?, ?, 1, 'ACTIVE',
                          UTC_TIMESTAMP(6) - INTERVAL 1 HOUR,
                          UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)
                """,
                activityId,
                "ACT-" + activityId,
                productId,
                Math.max(initialStock, mysqlStock),
                mysqlStock
        );
        redis.opsForHash().putAll(
                SeckillRedisKeys.activityMetadata(activityId),
                Map.of(
                        "schemaVersion", "1",
                        "activityId", Long.toString(activityId),
                        "productId", Long.toString(productId),
                        "initialAvailableStock", Integer.toString(initialStock),
                        "initialCatalogStock", Integer.toString(initialStock)
                )
        );
        redis.opsForValue().set(
                SeckillRedisKeys.availableStock(activityId),
                Integer.toString(initialStock)
        );
    }

    private void appendAccepted(Accepted event) {
        redis.opsForSet().add(SeckillRedisKeys.reservationStreamRegistry(), event.stream());
        redis.opsForZSet().add(SeckillRedisKeys.reconciliationStreamIndex(), event.stream(), 0);
        redis.opsForHash().increment(SeckillRedisKeys.activityMetadata(event.activityId()), "inventoryRevision", 1);
        redis.opsForHash().putAll(event.reservationKey(), event.reservationHash());
        redis.opsForValue().set(event.userKey(), event.reservationNo());
        redis.opsForValue().decrement(
                SeckillRedisKeys.availableStock(event.activityId()),
                event.quantity()
        );
        appendRaw(event.stream(), event.fields());
    }

    private void appendRaw(String stream, Map<String, String> fields) {
        redis.opsForStream().add(MapRecord.create(stream, fields));
    }

    private Accepted accepted(
            long seed,
            long activityId,
            long productId,
            long userId,
            int quantity,
            String unitPrice
    ) {
        String reservationNo = reservationNo(seed);
        String idempotencyHash = sha256("idempotency-" + seed);
        String fingerprint = sha256(
                activityId + "|" + userId + "|" + productId + "|" + quantity + "|" + unitPrice
        );
        return new Accepted(
                eventId(seed),
                reservationNo,
                activityId,
                userId,
                productId,
                quantity,
                unitPrice,
                1,
                System.currentTimeMillis(),
                "seckill-test-" + seed,
                idempotencyHash,
                fingerprint
        );
    }

    private void pollUntilOrders(int expected) {
        for (int attempt = 0; attempt < 30 && count("sales_order") < expected; attempt++) {
            consumer.poll();
        }
        assertThat(count("sales_order")).isEqualTo(expected);
    }

    private void pollUntilPendingEmpty(String stream) {
        for (int attempt = 0; attempt < 30; attempt++) {
            consumer.poll();
            if (pending(stream) == 0 && groupCaughtUp(stream)) {
                return;
            }
            if (attempt == 0) {
                awaitClaimablePending(stream);
            }
        }
        assertThat(pending(stream)).isZero();
        assertThat(groupCaughtUp(stream)).isTrue();
    }

    private boolean groupCaughtUp(String stream) {
        String lastGeneratedId = redis.opsForStream().info(stream).lastGeneratedId();
        return redis.opsForStream().groups(stream).stream()
                .filter(group -> properties.getGroupName().equals(group.groupName()))
                .anyMatch(group -> lastGeneratedId.equals(group.lastDeliveredId()));
    }

    private void awaitRetryAndClaim(String stream) {
        awaitClaimablePending(stream);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
            consumer.poll();
            assertThat(pending(stream)).isZero();
        });
    }

    private void awaitClaimablePending(String stream) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
            PendingMessages messages = redis.opsForStream().pending(
                    stream,
                    properties.getGroupName(),
                    Range.unbounded(),
                    1
            );
            assertThat(messages).isNotEmpty();
            assertThat(messages.get(0).getElapsedTimeSinceLastDelivery())
                    .isGreaterThanOrEqualTo(properties.getClaimIdle());
        });
    }

    private long pending(String stream) {
        PendingMessagesSummary summary =
                redis.opsForStream().pending(stream, properties.getGroupName());
        return summary == null ? 0 : summary.getTotalPendingMessages();
    }

    private int count(String table) {
        return integer("SELECT COUNT(*) FROM " + table);
    }

    private int countWhere(String table, String where) {
        return integer("SELECT COUNT(*) FROM " + table + " WHERE " + where);
    }

    private int integer(String sql, Object... arguments) {
        Integer result = jdbc.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private BusinessSnapshot snapshot(Accepted event) {
        return new BusinessSnapshot(
                integer("SELECT available_stock FROM flash_sale_activity WHERE activity_id = ?",
                        event.activityId()),
                integer("SELECT stock FROM catalog_product WHERE product_id = ?", event.productId()),
                count("sales_order"),
                count("sale_reservation"),
                redis.opsForValue().get(SeckillRedisKeys.availableStock(event.activityId())),
                redis.opsForValue().get(event.userKey()),
                new LinkedHashMap<>(redis.opsForHash().entries(event.reservationKey()))
        );
    }

    private static String eventId(long seed) {
        return "evt_" + String.format("%032x", seed);
    }

    private static String reservationNo(long seed) {
        return "rsv_" + String.format("%032x", seed);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void pause(String containerId) {
        DockerClientFactory.instance().client().pauseContainerCmd(containerId).exec();
    }

    private static void unpause(String containerId) {
        DockerClientFactory.instance().client().unpauseContainerCmd(containerId).exec();
        if (containerId.equals(MYSQL.getContainerId())) {
            await().pollInterval(Duration.ofMillis(200))
                    .during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(30))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                try (var connection = DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                    assertThat(connection.isValid(2)).isTrue();
                }
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
            String jdbcUrl = MYSQL.getJdbcUrl();
            dataSource.setUrl(jdbcUrl
                    + (jdbcUrl.contains("?") ? "&" : "?")
                    + "connectTimeout=5000&socketTimeout=5000");
            dataSource.setUsername(MYSQL.getUsername());
            dataSource.setPassword(MYSQL.getPassword());
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory seckillRedisConnectionFactory() {
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                    REDIS.getHost(),
                    REDIS.getMappedPort(6379)
            );
            LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofSeconds(2))
                    .build();
            return new LettuceConnectionFactory(standalone, client);
        }

        @Bean(name = "seckillStringRedisTemplate")
        StringRedisTemplate seckillStringRedisTemplate(
                LettuceConnectionFactory seckillRedisConnectionFactory
        ) {
            return new StringRedisTemplate(seckillRedisConnectionFactory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SeckillOrderProperties seckillOrderProperties() {
            SeckillOrderProperties properties = new SeckillOrderProperties();
            properties.setReadBatch(25);
            properties.setReadBlock(Duration.ofMillis(10));
            properties.setDiscoveryInterval(Duration.ofMillis(10));
            properties.setClaimIdle(Duration.ofMillis(20));
            properties.setClaimBatch(25);
            properties.setRetryInitialBackoff(Duration.ofMillis(5));
            properties.setRetryMaxBackoff(Duration.ofMillis(20));
            properties.setRetryMultiplier(1.0);
            properties.setDeterministicFailureAttempts(1);
            properties.setReconciliationBatch(200);
            properties.setReconciliationDryRun(true);
            properties.setAutoRepair(false);
            return properties;
        }

        @Bean
        MutableFailpoint failpoint() {
            return new MutableFailpoint();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SeckillOrderMetrics seckillOrderMetrics(MeterRegistry meterRegistry) {
            return new SeckillOrderMetrics(meterRegistry);
        }

        @Bean
        SeckillRedisReservationGateway reservationGateway(
                StringRedisTemplate seckillStringRedisTemplate
        ) {
            return new SeckillRedisReservationGateway(seckillStringRedisTemplate);
        }

        @Bean
        SeckillProcessingService processingService(
                JdbcTemplate jdbcTemplate,
                ObjectMapper objectMapper,
                SeckillOrderProperties properties,
                MutableFailpoint failpoint
        ) {
            return new SeckillProcessingService(
                    jdbcTemplate,
                    objectMapper,
                    properties,
                    failpoint
            );
        }

        @Bean
        ReservationStreamConsumer reservationStreamConsumer(
                StringRedisTemplate seckillStringRedisTemplate,
                SeckillOrderProperties properties,
                SeckillRedisReservationGateway gateway,
                SeckillProcessingService processingService,
                MutableFailpoint failpoint,
                SeckillOrderMetrics metrics
        ) {
            return new ReservationStreamConsumer(
                    seckillStringRedisTemplate,
                    properties,
                    gateway,
                    processingService,
                    failpoint,
                    metrics
            );
        }

        @Bean
        SeckillReconciliationService reconciliationService(
                StringRedisTemplate seckillStringRedisTemplate,
                JdbcTemplate jdbcTemplate,
                SeckillOrderProperties properties,
                SeckillRedisReservationGateway gateway,
                SeckillProcessingService processingService,
                SeckillOrderMetrics metrics
        ) {
            return new SeckillReconciliationService(
                    seckillStringRedisTemplate,
                    jdbcTemplate,
                    properties,
                    gateway,
                    processingService,
                    metrics
            );
        }
    }

    enum FailurePoint {
        BEFORE_ORDER_COMMIT,
        AFTER_ORDER_COMMIT,
        AFTER_FINALIZE,
        AFTER_COMPENSATION_INTENT,
        AFTER_REDIS_COMPENSATION,
        BEFORE_COMPENSATION_COMMIT
    }

    static final class MutableFailpoint implements SeckillProcessingFailpoint {
        private final AtomicReference<FailurePoint> point = new AtomicReference<>();

        void failOnceAt(FailurePoint failurePoint) {
            point.set(failurePoint);
        }

        void clear() {
            point.set(null);
        }

        @Override
        public void beforeOrderCommit(ReservationAcceptedEvent event) {
            fail(FailurePoint.BEFORE_ORDER_COMMIT);
        }

        @Override
        public void afterOrderCommitBeforeFinalize(ReservationAcceptedEvent event) {
            fail(FailurePoint.AFTER_ORDER_COMMIT);
        }

        @Override
        public void afterFinalizeBeforeAck(ReservationAcceptedEvent event) {
            fail(FailurePoint.AFTER_FINALIZE);
        }

        @Override
        public void afterCompensationIntent(ReservationAcceptedEvent event) {
            fail(FailurePoint.AFTER_COMPENSATION_INTENT);
        }

        @Override
        public void afterRedisCompensation(ReservationAcceptedEvent event) {
            fail(FailurePoint.AFTER_REDIS_COMPENSATION);
        }

        @Override
        public void beforeCompensationCommit(ReservationAcceptedEvent event) {
            fail(FailurePoint.BEFORE_COMPENSATION_COMMIT);
        }

        private void fail(FailurePoint expected) {
            if (point.compareAndSet(expected, null)) {
                throw new InjectedFailure(expected.name());
            }
        }
    }

    static final class InjectedFailure extends RuntimeException {
        InjectedFailure(String point) {
            super(point);
        }
    }

    record BusinessSnapshot(
            int mysqlActivityStock,
            int mysqlCatalogStock,
            int orders,
            int reservations,
            String redisStock,
            String redisUserSlot,
            Map<Object, Object> redisReservation
    ) {
    }

    record Accepted(
            String eventId,
            String reservationNo,
            long activityId,
            long userId,
            long productId,
            int quantity,
            String unitPrice,
            int activityVersion,
            long occurredAtMs,
            String requestId,
            String idempotencyKeyHash,
            String requestFingerprint
    ) {
        String stream() {
            return SeckillRedisKeys.reservationStream(activityId);
        }

        String reservationKey() {
            return SeckillRedisKeys.reservation(activityId, reservationNo);
        }

        String userKey() {
            return SeckillRedisKeys.userReservation(activityId, userId);
        }

        Accepted withEventId(String replacement) {
            return new Accepted(
                    replacement,
                    reservationNo,
                    activityId,
                    userId,
                    productId,
                    quantity,
                    unitPrice,
                    activityVersion,
                    occurredAtMs,
                    requestId,
                    idempotencyKeyHash,
                    requestFingerprint
            );
        }

        Map<String, String> fields() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("schemaVersion", "1");
            values.put("eventType", "RESERVATION_ACCEPTED");
            values.put("eventId", eventId);
            values.put("reservationNo", reservationNo);
            values.put("activityId", Long.toString(activityId));
            values.put("userId", Long.toString(userId));
            values.put("productId", Long.toString(productId));
            values.put("quantity", Integer.toString(quantity));
            values.put("unitPrice", new BigDecimal(unitPrice).setScale(2).toPlainString());
            values.put("currency", "CNY");
            values.put("status", "RESERVED");
            values.put("activityVersion", Integer.toString(activityVersion));
            values.put("occurredAtMs", Long.toString(occurredAtMs));
            values.put("requestId", requestId);
            values.put("traceparent",
                    "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01");
            values.put("tracestate", "hotshop=seckill-test");
            values.put("idempotencyKeyHash", idempotencyKeyHash);
            values.put("requestFingerprint", requestFingerprint);
            return values;
        }

        Map<String, String> reservationHash() {
            Map<String, String> values = new LinkedHashMap<>(fields());
            values.remove("eventType");
            values.remove("eventId");
            values.remove("occurredAtMs");
            return values;
        }
    }
}
