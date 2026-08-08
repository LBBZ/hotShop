package com.real.task.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import com.real.infrastructure.redis.SeckillRedisKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
class SeckillPaymentExpiredDeliveryContainerTest {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.0.7-management-alpine");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine")
            .withExposedPorts(6379);

    static CachingConnectionFactory rabbitConnection;
    static RabbitTemplate rabbit;
    static LettuceConnectionFactory redisConnection;
    static StringRedisTemplate redis;
    static ObjectMapper json;

    @BeforeAll
    static void setup() {
        RabbitMQConfig topology = new RabbitMQConfig(Duration.ofMinutes(15));
        rabbitConnection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        rabbitConnection.setUsername(RABBIT.getAdminUsername());
        rabbitConnection.setPassword(RABBIT.getAdminPassword());
        rabbitConnection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnection.setPublisherReturns(true);
        rabbit = topology.rabbitTemplate(rabbitConnection, topology.jsonMessageConverter());
        RabbitAdmin admin = new RabbitAdmin(rabbitConnection);
        admin.declareExchange(topology.businessExchange());
        admin.declareExchange(topology.seckillPaymentExpiredRetryExchange());
        admin.declareExchange(topology.seckillPaymentExpiredDeadExchange());
        admin.declareQueue(topology.seckillPaymentExpiredQueue());
        admin.declareQueue(topology.seckillPaymentExpiredRetryQueue());
        admin.declareQueue(topology.seckillPaymentExpiredDeadQueue());
        admin.declareBinding(topology.seckillPaymentExpiredBinding());
        admin.declareBinding(topology.seckillPaymentExpiredRetryBinding());
        admin.declareBinding(topology.seckillPaymentExpiredDeadBinding());

        redisConnection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnection.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnection);
        redis.afterPropertiesSet();
        json = new ObjectMapper().findAndRegisterModules();
    }

    @AfterAll
    static void closeConnections() {
        if (redisConnection != null) redisConnection.destroy();
        if (rabbitConnection != null) rabbitConnection.destroy();
    }

    @BeforeEach
    void reset() {
        while (rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_RETRY_QUEUE) != null) { }
        while (rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE) != null) { }
        try (var connection = Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void normalAndDuplicateDeliveriesAckWithOneRedisCompensationAndConsistentProjection() throws Exception {
        Fixture fixture = fixture();
        byte[] event = envelope(fixture);
        SeckillPaymentExpiredProjectionConsumer consumer = consumer(redis, properties(3));

        publish(event);
        consumeOne(consumer, null);
        assertProjection(fixture);
        publish(event);
        consumeOne(consumer, null);
        assertProjection(fixture);

        assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE)).isZero();
        assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_RETRY_QUEUE)).isZero();
        assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isZero();
    }

    @Test
    void schemaPoisonAndDeterministicRedisFactConflictEnterDedicatedDeadLetterQueue() throws Exception {
        Fixture fixture = fixture();
        SeckillPaymentExpiredProjectionConsumer consumer = consumer(redis, properties(3));
        Map<String, Object> poison = new LinkedHashMap<>();
        poison.put("schemaVersion", 1);
        poison.put("eventId", UUID.randomUUID().toString());
        poison.put("eventType", "SECKILL_PAYMENT_EXPIRED");
        poison.put("aggregateType", "ORDER");
        poison.put("aggregateId", fixture.orderId());
        poison.put("occurredAt", Instant.now().toString());
        poison.put("payload", Map.of("schemaVersion", 1));
        poison.put("extra", true);
        publish(json.writeValueAsBytes(poison));
        consumeOne(consumer, null);

        redis.opsForHash().put(SeckillRedisKeys.reservation(fixture.activityId(), fixture.reservationNo()),
                "orderId", "ord_conflicting_fact");
        publish(envelope(fixture));
        consumeOne(consumer, null);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isEqualTo(2));
        assertThat(rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isNotNull();
        assertThat(rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isNotNull();
        assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isZero();
    }

    @Test
    void temporaryRedisFailureUsesPersistentRetryThenRecovers() throws Exception {
        Fixture fixture = fixture();
        byte[] event = envelope(fixture);
        SeckillPaymentExpiredDeliveryProperties properties = properties(3);
        try (UnavailableRedis unavailable = unavailableRedis()) {
            publish(event);
            GetResponse first = consumeOne(consumer(unavailable.template(), properties), null);
            assertThat(first.getProps().getHeaders()).isNullOrEmpty();

            GetResponse retried = consumeOne(consumer(redis, properties), 1);
            assertThat(retried.getProps().getDeliveryMode()).isEqualTo(2);
        }
        assertProjection(fixture);
        assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isZero();
    }

    @Test
    void persistentRedisFailureStopsAtConfiguredLimitAndDeadLettersOnce() throws Exception {
        int maxAttempts = 3;
        Fixture fixture = fixture();
        SeckillPaymentExpiredDeliveryProperties properties = properties(maxAttempts);
        try (UnavailableRedis unavailable = unavailableRedis()) {
            SeckillPaymentExpiredProjectionConsumer consumer = consumer(unavailable.template(), properties);
            publish(envelope(fixture));
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                consumeOne(consumer, attempt == 1 ? null : attempt - 1);
            }
        }

        Message dead = await().atMost(Duration.ofSeconds(10))
                .until(() -> rabbit.receive(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE), Objects::nonNull);
        assertThat(dead).isNotNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE)).isZero();
            assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_RETRY_QUEUE)).isZero();
            assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isZero();
        });
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(fixture.activityId()))).isEqualTo("9");
        assertThat(redis.opsForHash().get(
                SeckillRedisKeys.reservation(fixture.activityId(), fixture.reservationNo()), "status"))
                .isEqualTo("ORDER_CREATED");
        assertThat(redis.opsForValue().get(
                SeckillRedisKeys.userReservation(fixture.activityId(), fixture.userId())))
                .isEqualTo(fixture.reservationNo());
    }

    @Test
    void retryPublishConfirmFailureLeavesOriginalUnackedAndRecoverable() throws Exception {
        Fixture fixture = fixture();
        byte[] event = envelope(fixture);
        SeckillPaymentExpiredDeliveryProperties properties = properties(3);
        RabbitMQConfig topology = new RabbitMQConfig(Duration.ofMinutes(15));
        RabbitAdmin admin = new RabbitAdmin(rabbitConnection);
        try (UnavailableRedis unavailable = unavailableRedis()) {
            publish(event);
            var connection = rabbitConnection.createConnection();
            var channel = connection.createChannel(false);
            GetResponse original = awaitGet(channel, RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE);
            admin.deleteExchange(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_RETRY_EXCHANGE);
            assertThatThrownBy(() -> consumer(unavailable.template(), properties)
                    .consume(springMessage(original), channel)).isInstanceOf(RuntimeException.class);
            ((ChannelProxy) channel).getTargetChannel().abort();
            connection.close();

            admin.declareExchange(topology.seckillPaymentExpiredRetryExchange());
            admin.declareBinding(topology.seckillPaymentExpiredRetryBinding());
            var recoveredConnection = rabbitConnection.createConnection();
            var recoveredChannel = recoveredConnection.createChannel(false);
            try {
                GetResponse redelivered = awaitGet(recoveredChannel,
                        RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE);
                assertThat(redelivered.getEnvelope().isRedeliver()).isTrue();
                consumer(redis, properties).consume(springMessage(redelivered), recoveredChannel);
            } finally {
                recoveredChannel.close();
                recoveredConnection.close();
            }
        } finally {
            admin.declareExchange(topology.seckillPaymentExpiredRetryExchange());
            admin.declareBinding(topology.seckillPaymentExpiredRetryBinding());
        }
        assertProjection(fixture);
        assertThat(messageCount(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE)).isZero();
    }

    @Test
    void invalidRetryConfigurationFailsValidation() {
        SeckillPaymentExpiredDeliveryProperties invalid = new SeckillPaymentExpiredDeliveryProperties();
        invalid.setMaxDeliveryAttempts(0);
        assertThatThrownBy(invalid::validate).isInstanceOf(IllegalStateException.class);
        invalid.setMaxDeliveryAttempts(21);
        assertThatThrownBy(invalid::validate).isInstanceOf(IllegalStateException.class);
        invalid.setMaxDeliveryAttempts(3);
        invalid.setRetryDelay(Duration.ZERO);
        assertThatThrownBy(invalid::validate).isInstanceOf(IllegalStateException.class);
    }

    private SeckillPaymentExpiredProjectionConsumer consumer(StringRedisTemplate template,
            SeckillPaymentExpiredDeliveryProperties properties) {
        return new SeckillPaymentExpiredProjectionConsumer(json, template, rabbit, properties);
    }

    private SeckillPaymentExpiredDeliveryProperties properties(int maxAttempts) {
        SeckillPaymentExpiredDeliveryProperties properties = new SeckillPaymentExpiredDeliveryProperties();
        properties.setRetryDelay(Duration.ofMillis(100));
        properties.setConfirmTimeout(Duration.ofSeconds(2));
        properties.setMaxDeliveryAttempts(maxAttempts);
        properties.validate();
        return properties;
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long activityId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000) + 1;
        long userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000) + 1;
        long productId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000) + 1;
        Fixture fixture = new Fixture("ord_" + suffix, "rsv_" + suffix,
                activityId, userId, productId, 1);
        redis.opsForValue().set(SeckillRedisKeys.availableStock(activityId), "9");
        redis.opsForValue().set(SeckillRedisKeys.userReservation(activityId, userId), fixture.reservationNo());
        redis.opsForHash().putAll(SeckillRedisKeys.reservation(activityId, fixture.reservationNo()), Map.of(
                "reservationNo", fixture.reservationNo(),
                "activityId", Long.toString(activityId),
                "userId", Long.toString(userId),
                "productId", Long.toString(productId),
                "quantity", Integer.toString(fixture.quantity()),
                "status", "ORDER_CREATED",
                "orderId", fixture.orderId()));
        return fixture;
    }

    private byte[] envelope(Fixture fixture) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("orderId", fixture.orderId());
        payload.put("reservationNo", fixture.reservationNo());
        payload.put("userId", fixture.userId());
        payload.put("activityId", fixture.activityId());
        payload.put("productId", fixture.productId());
        payload.put("quantity", fixture.quantity());
        payload.put("reason", "PAYMENT_TIMEOUT");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("eventId", UUID.randomUUID().toString());
        root.put("eventType", "SECKILL_PAYMENT_EXPIRED");
        root.put("aggregateType", "ORDER");
        root.put("aggregateId", fixture.orderId());
        root.put("occurredAt", Instant.now().toString());
        root.put("payload", payload);
        return json.writeValueAsBytes(root);
    }

    private void publish(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(RabbitMQConfig.BUSINESS_EXCHANGE, "SECKILL_PAYMENT_EXPIRED",
                new Message(body, properties));
    }

    private GetResponse consumeOne(SeckillPaymentExpiredProjectionConsumer consumer,
            Integer expectedRetryHeader) throws Exception {
        var connection = rabbitConnection.createConnection();
        var channel = connection.createChannel(false);
        try {
            GetResponse response = awaitGet(channel, RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE);
            Object header = response.getProps().getHeaders() == null ? null
                    : response.getProps().getHeaders().get("x-hotshop-delivery-attempt");
            if (expectedRetryHeader == null) assertThat(header).isNull();
            else assertThat(((Number) header).intValue()).isEqualTo(expectedRetryHeader);
            consumer.consume(springMessage(response), channel);
            return response;
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

    private long messageCount(String queue) {
        Long count = rabbit.execute(channel -> channel.messageCount(queue));
        return count == null ? 0 : count;
    }

    private void assertProjection(Fixture fixture) {
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(fixture.activityId())))
                .isEqualTo("10");
        String reservationKey = SeckillRedisKeys.reservation(fixture.activityId(), fixture.reservationNo());
        assertThat(redis.opsForHash().get(reservationKey, "status")).isEqualTo("PAYMENT_EXPIRED");
        assertThat(redis.opsForHash().get(reservationKey, "reasonCode")).isEqualTo("PAYMENT_TIMEOUT");
        assertThat(redis.opsForValue().get(
                SeckillRedisKeys.userReservation(fixture.activityId(), fixture.userId()))).isNull();
    }

    private UnavailableRedis unavailableRedis() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", 1);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(250)).shutdownTimeout(Duration.ZERO).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new UnavailableRedis(factory, template);
    }

    private record Fixture(String orderId, String reservationNo, long activityId,
                           long userId, long productId, int quantity) { }
    private record UnavailableRedis(LettuceConnectionFactory factory,
                                    StringRedisTemplate template) implements AutoCloseable {
        @Override public void close() { factory.destroy(); }
    }
}
