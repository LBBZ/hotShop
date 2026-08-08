package com.real.task.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.real.common.enums.OrderStatus;
import com.real.common.exception.InventoryShortageException;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import com.real.domain.messaging.OutboxEvent;
import com.real.domain.messaging.OutboxMapper;
import com.real.domain.service.advance.OrderStateService;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import com.real.task.timeoutOrderTask.OrderTimeoutService;
import com.real.task.timeoutOrderTask.OrderTimeoutConsumer;
import com.real.task.timeoutOrderTask.OrderTimeoutConsumerFailpoint;
import com.rabbitmq.client.GetResponse;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.real.domain.payment.MockPaymentProperties;
import com.real.domain.payment.MockPaymentProvider;
import com.real.task.payment.MockPaymentCallbackDeliveryConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReliableMessagingContainerTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_reliable").withUsername("hotshop").withPassword("hotshop-test")
            .withCommand("--log-bin-trust-function-creators=1");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0.7-management-alpine");

    static JdbcTemplate jdbc;
    static OutboxMapper outbox;
    static OrderMapper orders;
    static ProductMapper products;
    static TransactionTemplate transactions;
    static DataSourceTransactionManager transactionManager;
    static CachingConnectionFactory rabbitConnection;
    static RabbitTemplate rabbit;
    static ObjectMapper json;

    @BeforeAll
    static void setup() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTypeAliasesPackage("com.real.domain.entity");
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/real/domain/mapper/*.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        assertThat(factory).isNotNull();
        factory.getConfiguration().addMapper(OutboxMapper.class);
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        outbox = sessions.getMapper(OutboxMapper.class);
        orders = sessions.getMapper(OrderMapper.class);
        products = sessions.getMapper(ProductMapper.class);

        rabbitConnection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        rabbitConnection.setUsername(RABBIT.getAdminUsername());
        rabbitConnection.setPassword(RABBIT.getAdminPassword());
        rabbitConnection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnection.setPublisherReturns(true);
        RabbitMQConfig topology = new RabbitMQConfig(Duration.ofMinutes(15));
        rabbit = topology.rabbitTemplate(rabbitConnection, topology.jsonMessageConverter());
        RabbitAdmin admin = new RabbitAdmin(rabbitConnection);
        admin.declareExchange(topology.businessExchange());
        admin.declareExchange(topology.timeoutScheduleExchange());
        admin.declareExchange(topology.timeoutReadyExchange());
        admin.declareExchange(topology.timeoutDeadExchange());
        admin.declareExchange(topology.mockCallbackExchange());
        admin.declareExchange(topology.mockCallbackRetryExchange());
        admin.declareExchange(topology.mockCallbackDeadExchange());
        admin.declareExchange(topology.seckillPaymentExpiredRetryExchange());
        admin.declareExchange(topology.seckillPaymentExpiredDeadExchange());
        admin.declareQueue(topology.orderCreatedQueue());
        admin.declareQueue(topology.reservationCompensatedQueue());
        admin.declareQueue(topology.orderCanceledQueue());
        admin.declareQueue(topology.timeoutDelayQueue());
        admin.declareQueue(topology.timeoutReadyQueue());
        admin.declareQueue(topology.timeoutDeadQueue());
        admin.declareQueue(topology.paymentSucceededQueue());
        admin.declareQueue(topology.paymentFailedQueue());
        admin.declareQueue(topology.paymentLateSucceededQueue());
        admin.declareQueue(topology.seckillPaymentExpiredQueue());
        admin.declareQueue(topology.seckillPaymentExpiredRetryQueue());
        admin.declareQueue(topology.seckillPaymentExpiredDeadQueue());
        admin.declareQueue(topology.mockCallbackQueue());
        admin.declareQueue(topology.mockCallbackRetryQueue());
        admin.declareQueue(topology.mockCallbackDeadQueue());
        admin.declareBinding(topology.orderCreatedBinding());
        admin.declareBinding(topology.reservationCompensatedBinding());
        admin.declareBinding(topology.orderCanceledBinding());
        admin.declareBinding(topology.timeoutScheduleBinding());
        admin.declareBinding(topology.timeoutReadyBinding());
        admin.declareBinding(topology.timeoutDeadBinding());
        admin.declareBinding(topology.paymentSucceededBinding());
        admin.declareBinding(topology.paymentFailedBinding());
        admin.declareBinding(topology.paymentLateSucceededBinding());
        admin.declareBinding(topology.seckillPaymentExpiredBinding());
        admin.declareBinding(topology.seckillPaymentExpiredRetryBinding());
        admin.declareBinding(topology.seckillPaymentExpiredDeadBinding());
        admin.declareBinding(topology.mockCallbackBinding());
        admin.declareBinding(topology.mockCallbackRetryBinding());
        admin.declareBinding(topology.mockCallbackDeadBinding());
        json = new ObjectMapper();
    }

    @AfterAll
    static void closeRabbitConnection() {
        if (rabbitConnection != null) rabbitConnection.destroy();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM payment_callback_nonce");
        jdbc.update("DELETE FROM payment_callback_ledger");
        jdbc.update("DELETE FROM payment_order");
        jdbc.update("DELETE FROM sales_order_item");
        jdbc.update("DELETE FROM sales_order");
        jdbc.update("DELETE FROM sale_reservation");
        jdbc.update("DELETE FROM flash_sale_activity");
        jdbc.update("DELETE FROM catalog_product");
        jdbc.update("DELETE FROM app_user");
        while (rabbit.receive(RabbitMQConfig.ORDER_CREATED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.RESERVATION_COMPENSATED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.ORDER_CANCELED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.TIMEOUT_DELAY_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.TIMEOUT_READY_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.TIMEOUT_DEAD_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.PAYMENT_SUCCEEDED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.PAYMENT_FAILED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.PAYMENT_LATE_SUCCEEDED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_RETRY_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.MOCK_CALLBACK_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.MOCK_CALLBACK_RETRY_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.MOCK_CALLBACK_DEAD_QUEUE) != null) { }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void ordinaryOrderCommitContainsOrderItemAndTwoOutboxRows() {
        long productId = product("COMMIT", 5);
        Order order = order(productId, 2);
        OrderStateService service = orderService();
        String orderId = transactions.execute(ignored -> service.createOrder(order));

        assertThat(orderId).isNotBlank();
        assertThat(singleInt("SELECT COUNT(*) FROM sales_order WHERE order_id=? AND expires_at IS NOT NULL", orderId)).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM sales_order_item WHERE order_id=?", orderId)).isOne();
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(3);
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", orderId)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id=?", String.class, orderId))
                .containsExactlyInAnyOrder("ORDER_CREATED", "LEGACY_ORDER_TIMEOUT_REQUESTED");
        Long expiresAt = jdbc.queryForObject("SELECT UNIX_TIMESTAMP(expires_at)*1000 FROM sales_order WHERE order_id=?", Long.class, orderId);
        assertThat(expiresAt).isBetween(System.currentTimeMillis() + 890_000, System.currentTimeMillis() + 905_000);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void ordinaryOrderRollbackRestoresInventoryAndLeavesNoOrderOrOutbox() {
        long productId = product("ROLLBACK", 5);
        Order order = new Order();
        order.setUserId(42L);
        order.setItems(List.of(item(productId, 2), item(9_999_999L, 1)));
        assertThatThrownBy(() -> transactions.execute(ignored -> orderService().createOrder(order)))
                .isInstanceOf(InventoryShortageException.class);

        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(5);
        assertThat(singleInt("SELECT COUNT(*) FROM sales_order WHERE order_id=?", order.getOrderId())).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", order.getOrderId())).isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void realBrokerAckPublishesPersistentStableEnvelopeAndMarksPublished() throws Exception {
        String eventId = insertEvent("ORDER_CREATED", "order-published", payload("order-published", System.currentTimeMillis() + 60_000));
        OutboxPublisher publisher = publisher(Duration.ofSeconds(30));
        List<OutboxEvent> claimed = publisher.claimBatch();
        assertThat(claimed).hasSize(1);
        publisher.publish(claimed.getFirst());

        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId))
                .isEqualTo("PUBLISHED");
        Message received = await().atMost(Duration.ofSeconds(10)).until(
                () -> rabbit.receive(RabbitMQConfig.ORDER_CREATED_QUEUE), message -> message != null);
        assertThat(received.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
        JsonNode envelope = json.readTree(received.getBody());
        assertThat(envelope.path("schemaVersion").asInt()).isOne();
        assertThat(envelope.path("eventId").asText()).isEqualTo(eventId);
        assertThat(envelope.path("eventType").asText()).isEqualTo("ORDER_CREATED");
        assertThat(envelope.path("aggregateType").asText()).isEqualTo("ORDER");
        assertThat(envelope.path("aggregateId").asText()).isEqualTo("order-published");
        assertThat(envelope.path("occurredAt").asText()).isNotBlank();
        assertThat(envelope.path("payload").path("orderId").asText()).isEqualTo("order-published");
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void ttlAndDeadLetterExchangeDeliverTimeoutToReadyQueue() {
        String orderId = "order-timeout-route";
        insertEvent("LEGACY_ORDER_TIMEOUT_REQUESTED", orderId,
                payload(orderId, System.currentTimeMillis() + 300));
        OutboxPublisher publisher = publisher(Duration.ofSeconds(30));
        publisher.claimBatch().forEach(publisher::publish);

        Message ready = await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> rabbit.receive(RabbitMQConfig.TIMEOUT_READY_QUEUE), message -> message != null);
        assertThat(ready).isNotNull();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='PUBLISHED'", orderId)).isOne();
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void concurrentPublishersNeverOwnSameEffectiveLeaseAndExpiredLeaseIsFenced() throws Exception {
        String eventId = insertEvent("ORDER_CREATED", "order-lease", payload("order-lease", System.currentTimeMillis() + 60_000));
        OutboxPublisher first = publisher(Duration.ofSeconds(30));
        OutboxPublisher second = publisher(Duration.ofSeconds(30));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<List<OutboxEvent>> a = pool.submit(() -> { start.await(); return first.claimBatch(); });
            Future<List<OutboxEvent>> b = pool.submit(() -> { start.await(); return second.claimBatch(); });
            start.countDown();
            List<OutboxEvent> owned = new java.util.ArrayList<>();
            owned.addAll(a.get(10, TimeUnit.SECONDS)); owned.addAll(b.get(10, TimeUnit.SECONDS));
            assertThat(owned).hasSize(1);
            OutboxEvent old = owned.getFirst();
            jdbc.update("UPDATE outbox_event SET lease_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE event_id=?", eventId);
            OutboxEvent replacement = second.claimBatch().getFirst();
            assertThat(replacement.leaseToken()).isNotEqualTo(old.leaseToken());
            assertThat(replacement.version()).isGreaterThan(old.version());
            assertThat(outbox.published(old.outboxId(), old.leaseToken(), old.version(), LocalDateTime.now(ZoneOffset.UTC))).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void duplicateTimeoutHasOneCancellationOneRestockAndOneCancellationEvent() {
        long productId = product("IDEMPOTENT", 5);
        String orderId = legacyOrder(productId, null, "PENDING", true);
        OrderTimeoutService service = new OrderTimeoutService(jdbc, json);
        OrderTimeoutService.TimeoutEvent event = timeoutEvent(orderId);
        transactions.executeWithoutResult(ignored -> service.process(event));
        transactions.executeWithoutResult(ignored -> service.process(event));

        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId)).isEqualTo("CANCELED");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(6);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'", orderId)).isOne();
        Map<String, Object> audit = jdbc.queryForMap("""
            SELECT actor_type,action,resource_type,resource_id,result,source,CAST(state_summary AS CHAR) state_summary
              FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?
            """, orderId);
        assertThat(audit).containsEntry("actor_type", "SYSTEM")
                .containsEntry("action", "INVENTORY_COMPENSATED")
                .containsEntry("resource_type", "SALES_ORDER")
                .containsEntry("resource_id", orderId)
                .containsEntry("result", "SUCCESS")
                .containsEntry("source", "TASK");
        assertThat(audit.get("state_summary").toString())
                .contains("ORDINARY").contains("PAYMENT_TIMEOUT")
                .doesNotContain("secret").doesNotContain("nonce").doesNotContain("signature");
        assertThat(singleInt("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                orderId)).isOne();
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void timeoutTransactionRollbackCanRetry() {
        long productId = product("ROLLBACK-CONSUMER", 5);
        String legacy = legacyOrder(productId, null, "PENDING", true);
        String paymentNo = "MOCK_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,expires_at)
            SELECT ?,order_id,'MOCK',total_amount,currency,'PENDING',expires_at FROM sales_order WHERE order_id=?
            """, paymentNo, legacy);
        OrderTimeoutService service = new OrderTimeoutService(jdbc, json);
        var event = timeoutEvent(legacy);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            service.process(event); throw new IllegalStateException("fail after business work");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, legacy)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE payment_no=?", String.class, paymentNo)).isEqualTo("PENDING");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(5);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", legacy)).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                legacy)).isZero();
        transactions.executeWithoutResult(ignored -> service.process(event));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, legacy)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE payment_no=?", String.class, paymentNo)).isEqualTo("CLOSED");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(6);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'",
                legacy)).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                legacy)).isOne();
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    void failedIsNotAutomaticallyClaimedAndManualReplayResetsOnlyConsecutiveHistory() {
        String eventId = insertEvent("ORDER_CREATED", "order-manual-replay",
                payload("order-manual-replay", System.currentTimeMillis() + 60_000));
        jdbc.update("""
                UPDATE outbox_event SET status='FAILED',publish_attempts=8,consecutive_attempts=8,
                    manual_replay_count=2,failure_category='BROKER_NACK',last_error='BROKER_NACK'
                 WHERE event_id=?
                """, eventId);
        OutboxPublisher publisher = publisher(Duration.ofSeconds(30));
        assertThat(publisher.claimBatch()).isEmpty();

        jdbc.update("""
                UPDATE outbox_event SET status='NEW',consecutive_attempts=0,
                    manual_replay_count=manual_replay_count+1,available_at=UTC_TIMESTAMP(6),
                    lease_token=NULL,lease_expires_at=NULL,failure_category=NULL,last_error=NULL,
                    version=version+1 WHERE event_id=? AND status='FAILED'
                """, eventId);
        OutboxEvent replay = publisher.claimBatch().getFirst();
        assertThat(replay.publishAttempts()).isEqualTo(9);
        assertThat(replay.consecutiveAttempts()).isOne();
        publisher.publish(replay);
        assertThat(jdbc.queryForMap("SELECT status,publish_attempts,consecutive_attempts,manual_replay_count "
                + "FROM outbox_event WHERE event_id=?", eventId))
                .containsEntry("status", "PUBLISHED")
                .containsEntry("publish_attempts", 9)
                .containsEntry("consecutive_attempts", 1)
                .containsEntry("manual_replay_count", 3);
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    void exhaustedExpiredPublishingReliablyTransitionsToFailed() {
        String eventId = insertEvent("ORDER_CREATED", "order-exhausted",
                payload("order-exhausted", System.currentTimeMillis() + 60_000));
        jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING',publish_attempts=8,consecutive_attempts=8,
                    lease_token=?,lease_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),version=4
                 WHERE event_id=?
                """, UUID.randomUUID().toString(), eventId);

        assertThat(publisher(Duration.ofSeconds(30)).claimBatch()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT status,failure_category,lease_token FROM outbox_event WHERE event_id=?",
                eventId)).containsEntry("status", "FAILED")
                .containsEntry("failure_category", "MAX_ATTEMPTS_EXHAUSTED")
                .containsEntry("lease_token", null);
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    void afterClaimCrashLeavesPublishingUntilAnotherPublisherTakesOver() {
        String eventId = insertEvent("ORDER_CREATED", "order-after-claim",
                payload("order-after-claim", System.currentTimeMillis() + 60_000));
        OutboxEvent claimed = publisher(Duration.ofSeconds(30)).claimBatch().getFirst();
        OutboxPublisher crashing = publisher(Duration.ofSeconds(30), new OutboxPublishFailpoint() {
            @Override public void afterClaim(OutboxEvent ignored) {
                throw new OutboxPublisherCrashException("simulated process death after claim");
            }
        });
        assertThatThrownBy(() -> crashing.publish(claimed))
                .isInstanceOf(OutboxPublisherCrashException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId))
                .isEqualTo("PUBLISHING");
        jdbc.update("UPDATE outbox_event SET lease_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE event_id=?",
                eventId);
        OutboxEvent takeover = publisher(Duration.ofSeconds(30)).claimBatch().getFirst();
        assertThat(takeover.leaseToken()).isNotEqualTo(claimed.leaseToken());
        publisher(Duration.ofSeconds(30)).publish(takeover);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    @org.junit.jupiter.api.Order(11)
    void brokerAckCrashThenLeaseReplayProducesDuplicateButInboxKeepsOneBusinessEffect() throws Exception {
        long productId = product("ACK-CRASH", 5);
        String orderId = legacyOrder(productId, null, "PENDING", true);
        OrderTimeoutService.TimeoutEvent timeout = timeoutEvent(orderId);
        insertEvent("LEGACY_ORDER_TIMEOUT_REQUESTED", orderId,
                payload(orderId, timeout.expiresAtMs()));
        OutboxEvent firstLease = publisher(Duration.ofSeconds(30)).claimBatch().getFirst();
        OutboxPublisher crashAfterAck = publisher(Duration.ofSeconds(30), new OutboxPublishFailpoint() {
            @Override public void afterBrokerConfirm(OutboxEvent ignored) {
                throw new OutboxPublisherCrashException("simulated process death after broker ack");
            }
        });
        assertThatThrownBy(() -> crashAfterAck.publish(firstLease))
                .isInstanceOf(OutboxPublisherCrashException.class);
        Message first = await().atMost(Duration.ofSeconds(10)).until(
                () -> rabbit.receive(RabbitMQConfig.TIMEOUT_READY_QUEUE), Objects::nonNull);
        jdbc.update("UPDATE outbox_event SET lease_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE event_id=?",
                firstLease.eventId());
        OutboxEvent replay = publisher(Duration.ofSeconds(30)).claimBatch().getFirst();
        publisher(Duration.ofSeconds(30)).publish(replay);
        Message second = await().atMost(Duration.ofSeconds(10)).until(
                () -> rabbit.receive(RabbitMQConfig.TIMEOUT_READY_QUEUE), Objects::nonNull);
        assertThat(json.readTree(first.getBody()).path("eventId").asText()).isEqualTo(firstLease.eventId());
        assertThat(json.readTree(second.getBody()).path("eventId").asText()).isEqualTo(firstLease.eventId());

        OrderTimeoutService service = transactionalTimeoutService();
        OrderTimeoutService.TimeoutEvent duplicate = new OrderTimeoutService.TimeoutEvent(
                firstLease.eventId(), timeout.eventType(), timeout.aggregateType(), timeout.aggregateId(),
                timeout.orderId(), timeout.userId(), timeout.amount(), timeout.currency(), timeout.expiresAtMs(),
                timeout.timeoutAttempt(), timeout.occurredAt());
        assertThat(service.process(duplicate)).isEqualTo(OrderTimeoutService.ProcessResult.CANCELED);
        assertThat(service.process(duplicate)).isEqualTo(OrderTimeoutService.ProcessResult.DUPLICATE);
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(6);
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'",
                orderId)).isOne();
    }

    @Test
    @org.junit.jupiter.api.Order(12)
    void earlyDeliveryPersistsInboxAndDeterministicRescheduleTogether() {
        long productId = product("EARLY", 5);
        String orderId = legacyOrder(productId, null, "PENDING", false);
        OrderTimeoutService.TimeoutEvent early = timeoutEvent(orderId);

        assertThat(transactionalTimeoutService().process(early))
                .isEqualTo(OrderTimeoutService.ProcessResult.RESCHEDULED);
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId))
                .isEqualTo("PENDING");
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", early.eventId())).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? "
                + "AND event_type='LEGACY_ORDER_TIMEOUT_REQUESTED'", orderId)).isOne();
        OutboxEvent reschedule = publisher(Duration.ofSeconds(30)).claimBatch().getFirst();
        publisher(Duration.ofSeconds(30)).publish(reschedule);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long count = rabbit.execute(channel -> channel.messageCount(RabbitMQConfig.TIMEOUT_DELAY_QUEUE));
            assertThat(count).isEqualTo(1L);
        });
    }

    @Test
    @org.junit.jupiter.api.Order(13)
    void incompleteInventoryRestorationRollsBackCancellationInboxAndOutbox() {
        long productId = product("RESTORE-MISMATCH", 5);
        String orderId = legacyOrder(productId, null, "PENDING", true);
        jdbc.update("INSERT INTO sales_order_item(order_id,product_id,quantity,price,line_amount) "
                + "VALUES(?,999999999,1,10.00,10.00)", orderId);
        OrderTimeoutService.TimeoutEvent event = timeoutEvent(orderId);

        assertThatThrownBy(() -> transactionalTimeoutService().process(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inventory restoration row count mismatch");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId))
                .isEqualTo("PENDING");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(5);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", orderId)).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                orderId)).isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(14)
    void schemaPoisonAndDatabaseFactConflictAreRejectedToDeadLetterQueue() throws Exception {
        publishRawReady("{\"schemaVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        consumeOne(new OrderTimeoutConsumer(json, transactionalTimeoutService(),
                new OrderTimeoutConsumerFailpoint() { }));

        long productId = product("FACT-CONFLICT", 5);
        String orderId = legacyOrder(productId, null, "PENDING", true);
        OrderTimeoutService.TimeoutEvent event = timeoutEvent(orderId);
        publishRawReady(envelope(event, event.userId() + 1));
        consumeOne(new OrderTimeoutConsumer(json, transactionalTimeoutService(),
                new OrderTimeoutConsumerFailpoint() { }));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Long count = rabbit.execute(channel -> channel.messageCount(RabbitMQConfig.TIMEOUT_DEAD_QUEUE));
            assertThat(count).isEqualTo(2L);
        });
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId))
                .isEqualTo("PENDING");
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(15)
    void commitBeforeAckCrashRedeliversAndInboxRemovesDuplicateEffect() throws Exception {
        long productId = product("ACK-BOUNDARY", 5);
        String orderId = legacyOrder(productId, null, "PENDING", true);
        OrderTimeoutService.TimeoutEvent event = timeoutEvent(orderId);
        publishRawReady(envelope(event, event.userId()));

        var connection = rabbitConnection.createConnection();
        var channel = connection.createChannel(false);
        GetResponse delivery = awaitGet(channel, RabbitMQConfig.TIMEOUT_READY_QUEUE);
        OrderTimeoutConsumer crashing = new OrderTimeoutConsumer(json, transactionalTimeoutService(),
                new OrderTimeoutConsumerFailpoint() {
                    @Override public void afterCommitBeforeAck(OrderTimeoutService.TimeoutEvent ignored) {
                        throw new RuntimeException("simulated process death before ack");
                    }
                });
        assertThatThrownBy(() -> crashing.consume(springMessage(delivery), channel))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("before ack");
        ((ChannelProxy) channel).getTargetChannel().abort();
        connection.close();

        consumeOne(new OrderTimeoutConsumer(json, transactionalTimeoutService(),
                new OrderTimeoutConsumerFailpoint() { }));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId))
                .isEqualTo("CANCELED");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(6);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'",
                orderId)).isOne();
    }

    @Test
    @org.junit.jupiter.api.Order(16)
    void transientDatabaseFailureDoesNotAckAndMessageCanBeProcessedAfterRecovery() throws Exception {
        long productId = product("TRANSIENT", 5);
        String orderId = legacyOrder(productId, null, "PENDING", true);
        OrderTimeoutService.TimeoutEvent event = timeoutEvent(orderId);
        publishRawReady(envelope(event, event.userId()));
        OrderTimeoutService unavailable = new OrderTimeoutService(jdbc, json) {
            @Override public OrderTimeoutService.ProcessResult process(
                    OrderTimeoutService.TimeoutEvent ignored) {
                throw new TransientDataAccessResourceException("database temporarily unavailable");
            }
        };
        var connection = rabbitConnection.createConnection();
        var channel = connection.createChannel(false);
        GetResponse delivery = awaitGet(channel, RabbitMQConfig.TIMEOUT_READY_QUEUE);
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(json, unavailable,
                new OrderTimeoutConsumerFailpoint() { });
        assertThatThrownBy(() -> consumer.consume(springMessage(delivery), channel))
                .isInstanceOf(TransientDataAccessResourceException.class);
        ((ChannelProxy) channel).getTargetChannel().abort();
        connection.close();

        consumeOne(new OrderTimeoutConsumer(json, transactionalTimeoutService(),
                new OrderTimeoutConsumerFailpoint() { }));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId))
                .isEqualTo("CANCELED");
    }

    @Test
    @org.junit.jupiter.api.Order(17)
    void task08BusinessEventTypesPublishThroughReliableOutbox() {
        String orderId = "reservation-compensated-" + UUID.randomUUID();
        String eventId = insertEvent("RESERVATION_COMPENSATED", orderId,
                payload(orderId, System.currentTimeMillis() + 60_000));
        OutboxPublisher publisher = publisher(Duration.ofSeconds(30));
        publisher.claimBatch().forEach(publisher::publish);

        Message message = await().atMost(Duration.ofSeconds(10)).until(
                () -> rabbit.receive(RabbitMQConfig.RESERVATION_COMPENSATED_QUEUE), Objects::nonNull);
        assertThat(message).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    @org.junit.jupiter.api.Order(18)
    void brokerOutageDoesNotBlockOrderCommitAndFreshPublisherDeliversAfterRecovery() throws Exception {
        var stopped = RABBIT.execInContainer("rabbitmqctl", "stop_app");
        assertThat(stopped.getExitCode()).isZero();
        long productId = product("BROKER-OUTAGE", 5);
        String orderId = transactions.execute(ignored -> orderService().createOrder(order(productId, 1)));
        assertThat(singleInt("SELECT COUNT(*) FROM sales_order WHERE order_id=?", orderId)).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", orderId)).isEqualTo(2);

        try {
            publisher(Duration.ofSeconds(30)).poll();
            assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='NEW'", orderId))
                    .isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT failure_category FROM outbox_event WHERE aggregate_id=?",
                    String.class, orderId)).containsOnly("CONNECTION_FAILURE");
        } finally {
            var started = RABBIT.execInContainer("rabbitmqctl", "start_app");
            assertThat(started.getExitCode()).isZero();
            rabbitConnection.resetConnection();
        }

        jdbc.update("UPDATE outbox_event SET available_at=UTC_TIMESTAMP(6) WHERE aggregate_id=?", orderId);
        OutboxPublisher restartedTaskPublisher = publisher(Duration.ofSeconds(30));
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            restartedTaskPublisher.poll();
            assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND status='PUBLISHED'",
                    orderId)).isEqualTo(2);
        });
        assertThat(await().atMost(Duration.ofSeconds(10)).until(
                () -> rabbit.receive(RabbitMQConfig.ORDER_CREATED_QUEUE), Objects::nonNull)).isNotNull();
    }

    @Test
    @org.junit.jupiter.api.Order(19)
    void seckillTimeoutRollbackAndRetryAtomicallyCompensatesMysqlOutboxInboxAndAuditOnce() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long productId = product("SKAUD", 9);
        jdbc.update("""
            INSERT INTO flash_sale_activity(activity_code,product_id,sale_price,total_stock,available_stock,
              per_user_limit,status,starts_at,ends_at)
            VALUES(?,?,10.00,10,9,1,'ACTIVE',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),
              DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR))
            """, "ACT-" + suffix, productId);
        long activityId = jdbc.queryForObject(
                "SELECT activity_id FROM flash_sale_activity WHERE activity_code=?", Long.class, "ACT-" + suffix);
        String reservationNo = "rsv_" + suffix;
        String orderId = "ord_" + suffix;
        jdbc.update("""
            INSERT INTO sale_reservation(reservation_no,activity_id,user_id,product_id,quantity,reserved_amount,
              unit_price,currency,status,order_id,expires_at)
            VALUES(?,?,42,?,1,10.00,10.00,'CNY','ORDER_CREATED',?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR))
            """, reservationNo, activityId, productId, orderId);
        long reservationId = jdbc.queryForObject(
                "SELECT reservation_id FROM sale_reservation WHERE reservation_no=?", Long.class, reservationNo);
        jdbc.update("""
            INSERT INTO sales_order(order_id,user_id,reservation_id,total_amount,currency,status,expires_at)
            VALUES(?,42,?,10.00,'CNY','PENDING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND))
            """, orderId, reservationId);
        jdbc.update("INSERT INTO sales_order_item(order_id,product_id,quantity,price,line_amount) VALUES(?,?,1,10.00,10.00)",
                orderId, productId);
        String paymentNo = "MOCK_" + suffix;
        jdbc.update("""
            INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,expires_at)
            SELECT ?,order_id,'MOCK',total_amount,currency,'PENDING',expires_at FROM sales_order WHERE order_id=?
            """, paymentNo, orderId);
        OrderTimeoutService.TimeoutEvent event = timeoutEvent(orderId);
        OrderTimeoutService service = new OrderTimeoutService(jdbc, json);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            service.process(event);
            throw new IllegalStateException("fail after compensation audit and outbox");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("after compensation audit");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE payment_no=?", String.class, paymentNo)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM sale_reservation WHERE reservation_no=?", String.class, reservationNo)).isEqualTo("ORDER_CREATED");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(9);
        assertThat(singleInt("SELECT available_stock FROM flash_sale_activity WHERE activity_id=?", activityId)).isEqualTo(9);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", orderId)).isZero();
        assertThat(singleInt("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                reservationNo)).isZero();

        transactions.executeWithoutResult(ignored -> service.process(event));
        transactions.executeWithoutResult(ignored -> service.process(event));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE order_id=?", String.class, orderId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_order WHERE payment_no=?", String.class, paymentNo)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM sale_reservation WHERE reservation_no=?", String.class, reservationNo)).isEqualTo("CANCELED");
        assertThat(singleInt("SELECT stock FROM catalog_product WHERE product_id=?", productId)).isEqualTo(10);
        assertThat(singleInt("SELECT available_stock FROM flash_sale_activity WHERE activity_id=?", activityId)).isEqualTo(10);
        assertThat(singleInt("SELECT COUNT(*) FROM processed_event WHERE event_id=?", event.eventId())).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'",
                orderId)).isOne();
        assertThat(singleInt("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='SECKILL_PAYMENT_EXPIRED'",
                orderId)).isOne();
        Map<String, Object> audit = jdbc.queryForMap("""
            SELECT actor_type,action,resource_type,result,source,CAST(state_summary AS CHAR) state_summary
              FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?
            """, reservationNo);
        assertThat(audit).containsEntry("actor_type", "SYSTEM")
                .containsEntry("action", "INVENTORY_COMPENSATED")
                .containsEntry("resource_type", "SALE_RESERVATION")
                .containsEntry("result", "SUCCESS")
                .containsEntry("source", "TASK");
        assertThat(audit.get("state_summary").toString())
                .contains("SECKILL").contains("PAYMENT_TIMEOUT").contains(reservationNo);
        assertThat(singleInt("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                reservationNo)).isOne();
    }

    @Test
    @org.junit.jupiter.api.Order(20)
    void paymentEventsAndMockDeliveryAreDurablyRoutable() throws Exception {
        Map<String, String> queues = Map.of(
                "PAYMENT_SUCCEEDED", RabbitMQConfig.PAYMENT_SUCCEEDED_QUEUE,
                "PAYMENT_FAILED", RabbitMQConfig.PAYMENT_FAILED_QUEUE,
                "PAYMENT_LATE_SUCCEEDED", RabbitMQConfig.PAYMENT_LATE_SUCCEEDED_QUEUE,
                "SECKILL_PAYMENT_EXPIRED", RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE);
        for (Map.Entry<String, String> route : queues.entrySet()) {
            String aggregate = "payment-route-" + route.getKey();
            insertEvent(route.getKey(), aggregate,
                    json.writeValueAsString(Map.of("schemaVersion", 1, "paymentNo", aggregate)));
            publisher(Duration.ofSeconds(30)).claimBatch().forEach(publisher(Duration.ofSeconds(30))::publish);
            Message received = await().atMost(Duration.ofSeconds(10))
                    .until(() -> rabbit.receive(route.getValue()), Objects::nonNull);
            assertThat(received.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
        }

        String paymentNo = "MOCK_" + "1".repeat(32);
        String callbackId = UUID.randomUUID().toString();
        String payload = json.writeValueAsString(Map.of("schemaVersion", 1, "duplicateCount", 2,
                "callback", Map.of("callbackId", callbackId, "paymentNo", paymentNo,
                        "providerTransactionNo", "MOCK-TXN-" + "2".repeat(32), "outcome", "SUCCEEDED",
                        "amount", "10.00", "currency", "CNY", "occurredAt", Instant.now().toString())));
        String eventId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload)
            VALUES(?,'PAYMENT',?,'MOCK_PAYMENT_CALLBACK_REQUESTED',CAST(? AS JSON))
            """, eventId, paymentNo, payload);
        OutboxPublisher publisher = publisher(Duration.ofSeconds(30));
        publisher.claimBatch().forEach(publisher::publish);
        Message callback = await().atMost(Duration.ofSeconds(10))
                .until(() -> rabbit.receive(RabbitMQConfig.MOCK_CALLBACK_QUEUE), Objects::nonNull);
        assertThat(callback.getMessageProperties().getReceivedDeliveryMode().name()).isEqualTo("PERSISTENT");
        assertThat(json.readTree(callback.getBody()).path("payload").path("callback").path("callbackId").asText())
                .isEqualTo(callbackId);
    }

    @Test
    @org.junit.jupiter.api.Order(21)
    void temporaryPortal503RecoversAndDeterministic400DeadLetters()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider-callbacks/v1/mock-payment", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 503 : 200;
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.start();
        try {
            MockPaymentProperties properties = callbackProperties(server.getAddress().getPort(), 3);
            MockPaymentCallbackDeliveryConsumer consumer = new MockPaymentCallbackDeliveryConsumer(
                    json, new MockPaymentProvider(properties), properties, rabbit);
            byte[] envelope = callbackEnvelope();
            rabbit.send(RabbitMQConfig.MOCK_CALLBACK_EXCHANGE, RabbitMQConfig.MOCK_CALLBACK_ROUTING_KEY,
                    new Message(envelope));
            var connection = rabbitConnection.createConnection();
            var channel = connection.createChannel(false);
            try {
                consumer.consume(springMessage(awaitGet(channel, RabbitMQConfig.MOCK_CALLBACK_QUEUE)), channel);
                consumer.consume(springMessage(awaitGet(channel, RabbitMQConfig.MOCK_CALLBACK_QUEUE)), channel);
            } finally { channel.close(); connection.close(); }
            assertThat(calls).hasValue(2);

            server.removeContext("/provider-callbacks/v1/mock-payment");
            server.createContext("/provider-callbacks/v1/mock-payment", exchange -> {
                exchange.sendResponseHeaders(400, -1); exchange.close();
            });
            rabbit.send(RabbitMQConfig.MOCK_CALLBACK_EXCHANGE, RabbitMQConfig.MOCK_CALLBACK_ROUTING_KEY,
                    new Message(callbackEnvelope()));
            var secondConnection = rabbitConnection.createConnection();
            var secondChannel = secondConnection.createChannel(false);
            try {
                consumer.consume(springMessage(awaitGet(secondChannel, RabbitMQConfig.MOCK_CALLBACK_QUEUE)), secondChannel);
            } finally { secondChannel.close(); secondConnection.close(); }
            Message dead = await().atMost(Duration.ofSeconds(10))
                    .until(() -> rabbit.receive(RabbitMQConfig.MOCK_CALLBACK_DEAD_QUEUE), Objects::nonNull);
            assertThat(dead).isNotNull();
        } finally { server.stop(0); }
    }

    @Test
    @org.junit.jupiter.api.Order(22)
    void persistentPortal503StopsExactlyAtConfiguredAttemptsAndLeavesOnlyOneDeadLetter()
            throws Exception {
        int maxAttempts = 3;
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider-callbacks/v1/mock-payment", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            MockPaymentProperties properties = callbackProperties(server.getAddress().getPort(), maxAttempts);
            MockPaymentCallbackDeliveryConsumer consumer = new MockPaymentCallbackDeliveryConsumer(
                    json, new MockPaymentProvider(properties), properties, rabbit);
            rabbit.send(RabbitMQConfig.MOCK_CALLBACK_EXCHANGE, RabbitMQConfig.MOCK_CALLBACK_ROUTING_KEY,
                    new Message(callbackEnvelope()));
            var connection = rabbitConnection.createConnection();
            var channel = connection.createChannel(false);
            try {
                for (int deliveryAttempt = 1; deliveryAttempt <= maxAttempts; deliveryAttempt++) {
                    GetResponse response = awaitGet(channel, RabbitMQConfig.MOCK_CALLBACK_QUEUE);
                    Object retryHeader = response.getProps().getHeaders() == null ? null
                            : response.getProps().getHeaders().get("x-hotshop-delivery-attempt");
                    if (deliveryAttempt == 1) assertThat(retryHeader).isNull();
                    else assertThat(((Number) retryHeader).intValue()).isEqualTo(deliveryAttempt - 1);
                    consumer.consume(springMessage(response), channel);
                }
            } finally {
                channel.close();
                connection.close();
            }

            assertThat(calls).hasValue(maxAttempts);
            Message dead = await().atMost(Duration.ofSeconds(10))
                    .until(() -> rabbit.receive(RabbitMQConfig.MOCK_CALLBACK_DEAD_QUEUE), Objects::nonNull);
            assertThat(dead).isNotNull();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                Long mainCount = rabbit.execute(brokerChannel -> brokerChannel.messageCount(
                        RabbitMQConfig.MOCK_CALLBACK_QUEUE));
                Long retryCount = rabbit.execute(brokerChannel -> brokerChannel.messageCount(
                        RabbitMQConfig.MOCK_CALLBACK_RETRY_QUEUE));
                Long deadQueueCount = rabbit.execute(brokerChannel -> brokerChannel.messageCount(
                        RabbitMQConfig.MOCK_CALLBACK_DEAD_QUEUE));
                assertThat(mainCount).isZero();
                assertThat(retryCount).isZero();
                assertThat(deadQueueCount).isZero();
            });
            assertThat(calls).hasValue(maxAttempts);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(23)
    void retryPublishBrokerNackLeavesOriginalUnackedForRedelivery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider-callbacks/v1/mock-payment", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        RabbitMQConfig topology = new RabbitMQConfig(Duration.ofMinutes(15));
        RabbitAdmin admin = new RabbitAdmin(rabbitConnection);
        try {
            MockPaymentProperties properties = callbackProperties(server.getAddress().getPort(), 3);
            MockPaymentCallbackDeliveryConsumer consumer = new MockPaymentCallbackDeliveryConsumer(
                    json, new MockPaymentProvider(properties), properties, rabbit);
            rabbit.send(RabbitMQConfig.MOCK_CALLBACK_EXCHANGE, RabbitMQConfig.MOCK_CALLBACK_ROUTING_KEY,
                    new Message(callbackEnvelope()));
            var connection = rabbitConnection.createConnection();
            var channel = connection.createChannel(false);
            GetResponse original = awaitGet(channel, RabbitMQConfig.MOCK_CALLBACK_QUEUE);
            admin.deleteExchange(RabbitMQConfig.MOCK_CALLBACK_RETRY_EXCHANGE);
            assertThatThrownBy(() -> consumer.consume(springMessage(original), channel))
                    .isInstanceOf(RuntimeException.class);
            ((ChannelProxy) channel).getTargetChannel().abort();
            connection.close();

            admin.declareExchange(topology.mockCallbackRetryExchange());
            admin.declareBinding(topology.mockCallbackRetryBinding());
            var recoveredConnection = rabbitConnection.createConnection();
            var recoveredChannel = recoveredConnection.createChannel(false);
            try {
                GetResponse redelivered = awaitGet(recoveredChannel, RabbitMQConfig.MOCK_CALLBACK_QUEUE);
                assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();
                consumer.consume(springMessage(redelivered), recoveredChannel);
            } finally {
                recoveredChannel.close();
                recoveredConnection.close();
            }
            assertThat(calls).hasValue(2);
            Long deadCount = rabbit.execute(channelValue -> channelValue.messageCount(
                    RabbitMQConfig.MOCK_CALLBACK_DEAD_QUEUE));
            assertThat(deadCount).isZero();
        } finally {
            admin.declareExchange(topology.mockCallbackRetryExchange());
            admin.declareBinding(topology.mockCallbackRetryBinding());
            server.stop(0);
        }
    }

    private MockPaymentProperties callbackProperties(int port, int maxAttempts) {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true); properties.setSecret(UUID.randomUUID().toString());
        properties.setCallbackUrl(URI.create("http://127.0.0.1:" + port + "/provider-callbacks/v1/mock-payment"));
        properties.setRetryDelay(Duration.ofMillis(100));
        properties.setHttpTimeout(Duration.ofSeconds(2));
        properties.setMaxDeliveryAttempts(maxAttempts);
        return properties;
    }

    private byte[] callbackEnvelope() throws Exception {
        String paymentNo = "MOCK_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> callback = Map.of("callbackId", UUID.randomUUID().toString(),
                "paymentNo", paymentNo, "providerTransactionNo", "MOCK-TXN-" + UUID.randomUUID().toString().replace("-", ""),
                "outcome", "SUCCEEDED", "amount", "10.00", "currency", "CNY", "occurredAt", Instant.now().toString());
        return json.writeValueAsBytes(Map.of("schemaVersion", 1, "eventId", UUID.randomUUID().toString(),
                "eventType", "MOCK_PAYMENT_CALLBACK_REQUESTED", "aggregateType", "PAYMENT",
                "aggregateId", paymentNo, "occurredAt", Instant.now().toString(),
                "payload", Map.of("schemaVersion", 1, "callback", callback, "duplicateCount", 1)));
    }

    private static DataSource dataSource() {
        MysqlDataSource source = new MysqlDataSource();
        source.setURL(MYSQL.getJdbcUrl()); source.setUser(MYSQL.getUsername()); source.setPassword(MYSQL.getPassword());
        return source;
    }

    private OutboxPublisher publisher(Duration lease) {
        return publisher(lease, new OutboxPublishFailpoint() { });
    }

    private OutboxPublisher publisher(Duration lease, OutboxPublishFailpoint failpoint) {
        return new OutboxPublisher(outbox, rabbit, json, transactions,
                new OutboxPublisherProperties(true, 50, Duration.ofMillis(50), lease,
                        Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofSeconds(2), 2, 8),
                failpoint);
    }

    private OrderTimeoutService transactionalTimeoutService() {
        ProxyFactory proxy = new ProxyFactory(new OrderTimeoutService(jdbc, json));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (OrderTimeoutService) proxy.getProxy();
    }

    private void publishRawReady(byte[] body) {
        rabbit.execute(channel -> {
            channel.basicPublish(RabbitMQConfig.TIMEOUT_READY_EXCHANGE,
                    RabbitMQConfig.TIMEOUT_ROUTING_KEY,
                    com.rabbitmq.client.MessageProperties.PERSISTENT_TEXT_PLAIN, body);
            return null;
        });
    }

    private void consumeOne(OrderTimeoutConsumer consumer) throws Exception {
        var connection = rabbitConnection.createConnection();
        var channel = connection.createChannel(false);
        try {
            consumer.consume(springMessage(awaitGet(channel, RabbitMQConfig.TIMEOUT_READY_QUEUE)), channel);
        } finally {
            channel.close();
            connection.close();
        }
    }

    private GetResponse awaitGet(com.rabbitmq.client.Channel channel, String queue) {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .until(() -> channel.basicGet(queue, false), Objects::nonNull);
    }

    private Message springMessage(GetResponse delivery) {
        var properties = new DefaultMessagePropertiesConverter().toMessageProperties(
                delivery.getProps(), delivery.getEnvelope(), "UTF-8");
        return new Message(delivery.getBody(), properties);
    }

    private byte[] envelope(OrderTimeoutService.TimeoutEvent event, long userId) throws Exception {
        Map<String, Object> payload = Map.of(
                "schemaVersion", 1,
                "orderId", event.orderId(),
                "userId", userId,
                "amount", event.amount().toPlainString(),
                "currency", event.currency(),
                "expiresAtMs", event.expiresAtMs());
        return json.writeValueAsBytes(Map.of(
                "schemaVersion", 1,
                "eventId", event.eventId(),
                "eventType", event.eventType(),
                "aggregateType", event.aggregateType(),
                "aggregateId", event.aggregateId(),
                "occurredAt", event.occurredAt().toString(),
                "payload", payload));
    }

    private OrderStateService orderService() {
        return new OrderStateService(orders, products, outbox, json, Duration.ofMinutes(15));
    }

    private long product(String suffix, int stock) {
        jdbc.update("INSERT INTO catalog_product(sku,name,price,stock,status) VALUES(?,?,10.00,?,'ACTIVE')",
                "TASK09-" + suffix + "-" + UUID.randomUUID(), suffix, stock);
        return jdbc.queryForObject("SELECT product_id FROM catalog_product WHERE name=? ORDER BY product_id DESC LIMIT 1", Long.class, suffix);
    }

    private Order order(long productId, int quantity) {
        Order order = new Order(); order.setUserId(42L); order.setItems(List.of(item(productId, quantity))); return order;
    }

    private OrderItem item(long productId, int quantity) {
        OrderItem item = new OrderItem(); item.setProductId(productId); item.setQuantity(quantity); return item;
    }

    private String insertEvent(String type, String aggregateId, String payload) {
        String id = UUID.randomUUID().toString(); outbox.insert(id, "ORDER", aggregateId, type, payload); return id;
    }

    private String payload(String orderId, long expiresAtMs) {
        try {
            return json.writeValueAsString(Map.of("schemaVersion", 1, "orderId", orderId, "userId", 42,
                    "amount", "10.00", "currency", "CNY", "expiresAtMs", expiresAtMs));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String legacyOrder(long productId, Long reservationId, String status, boolean expired) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sales_order(order_id,user_id,reservation_id,total_amount,currency,status,expires_at) VALUES(?,42,?,10.00,'CNY',?,?)",
                id, reservationId, status, expired ? LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1) : LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15));
        jdbc.update("INSERT INTO sales_order_item(order_id,product_id,quantity,price,line_amount) VALUES(?,?,1,10.00,10.00)", id, productId);
        return id;
    }

    private OrderTimeoutService.TimeoutEvent timeoutEvent(String orderId) {
        long expiresAtMs = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',expires_at) DIV 1000 "
                        + "FROM sales_order WHERE order_id=?",
                Long.class, orderId);
        return new OrderTimeoutService.TimeoutEvent(UUID.randomUUID().toString(),
                "LEGACY_ORDER_TIMEOUT_REQUESTED", "ORDER", orderId, orderId,
                42L, new BigDecimal("10.00"), "CNY", expiresAtMs, 0, java.time.Instant.now());
    }

    private int singleInt(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args); return value == null ? 0 : value;
    }
}
