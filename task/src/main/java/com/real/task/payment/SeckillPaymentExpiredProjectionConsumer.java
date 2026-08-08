package com.real.task.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import com.real.infrastructure.redis.SeckillRedisKeys;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class SeckillPaymentExpiredProjectionConsumer {
    private static final Set<String> ENVELOPE = Set.of("schemaVersion", "eventId", "eventType",
            "aggregateType", "aggregateId", "occurredAt", "payload");
    private static final Set<String> PAYLOAD = Set.of("schemaVersion", "orderId", "reservationNo",
            "userId", "activityId", "productId", "quantity", "reason");
    private static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern RESERVATION_NO = Pattern.compile("^rsv_[0-9a-f]{32}$");
    private static final DefaultRedisScript<List> SCRIPT = script();
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final SeckillPaymentExpiredDeliveryProperties properties;

    public SeckillPaymentExpiredProjectionConsumer(ObjectMapper json,
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis,
            RabbitTemplate rabbit, SeckillPaymentExpiredDeliveryProperties properties) {
        this.json = json;
        this.redis = redis;
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @RabbitListener(queues = RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_QUEUE, ackMode = "MANUAL")
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Event event;
        try {
            event = parse(message.getBody());
        } catch (RuntimeException poison) {
            channel.basicReject(tag, false);
            return;
        }

        int attempt = headerAttempt(message) + 1;
        try {
            List<?> result = redis.execute(SCRIPT, List.of(
                            SeckillRedisKeys.reservation(event.activityId(), event.reservationNo()),
                            SeckillRedisKeys.availableStock(event.activityId()),
                            SeckillRedisKeys.userReservation(event.activityId(), event.userId())),
                    event.reservationNo(), event.orderId(), Long.toString(event.activityId()),
                    Long.toString(event.userId()), Long.toString(event.productId()),
                    Integer.toString(event.quantity()),
                    Long.toString(Instant.now().toEpochMilli()));
            if (result == null || result.isEmpty()) throw new RetryableProjectionFailure();
            String code = result.getFirst().toString();
            if (!Set.of("COMPENSATED", "IDEMPOTENT").contains(code)) {
                throw new DeterministicProjectionFailure();
            }
            channel.basicAck(tag, false);
        } catch (DeterministicProjectionFailure conflict) {
            channel.basicReject(tag, false);
        } catch (RuntimeException transientFailure) {
            if (attempt >= properties.getMaxDeliveryAttempts()) {
                channel.basicReject(tag, false);
                return;
            }
            republishForRetry(message.getBody(), attempt);
            channel.basicAck(tag, false);
        }
    }

    private void republishForRetry(byte[] body, int attempt) {
        MessageProperties outgoing = new MessageProperties();
        outgoing.setContentType("application/json");
        outgoing.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        outgoing.setExpiration(Long.toString(properties.getRetryDelay().toMillis()));
        outgoing.setHeader("x-hotshop-delivery-attempt", attempt);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_RETRY_EXCHANGE,
                RabbitMQConfig.SECKILL_PAYMENT_EXPIRED_ROUTING_KEY,
                new Message(body, outgoing), correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new RetryableProjectionFailure();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableProjectionFailure();
        } catch (Exception exception) {
            throw new RetryableProjectionFailure();
        }
    }

    private Event parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            exact(root, ENVELOPE);
            if (!integer(root.get("schemaVersion"), 1)
                    || !canonicalUuid(text(root, "eventId", 36))
                    || !"SECKILL_PAYMENT_EXPIRED".equals(text(root, "eventType", 32))
                    || !"ORDER".equals(text(root, "aggregateType", 16))) {
                throw new IllegalArgumentException();
            }
            String aggregateId = text(root, "aggregateId", 64);
            if (!ORDER_ID.matcher(aggregateId).matches()) throw new IllegalArgumentException();
            Instant.parse(text(root, "occurredAt", 35));

            JsonNode payload = root.get("payload");
            exact(payload, PAYLOAD);
            if (!integer(payload.get("schemaVersion"), 1)) throw new IllegalArgumentException();
            String orderId = text(payload, "orderId", 64);
            String reservationNo = text(payload, "reservationNo", 36);
            if (!aggregateId.equals(orderId) || !ORDER_ID.matcher(orderId).matches()
                    || !RESERVATION_NO.matcher(reservationNo).matches()
                    || !"PAYMENT_TIMEOUT".equals(text(payload, "reason", 32))) {
                throw new IllegalArgumentException();
            }
            long userId = positiveLong(payload.get("userId"));
            long activityId = positiveLong(payload.get("activityId"));
            long productId = positiveLong(payload.get("productId"));
            int quantity = positiveInt(payload.get("quantity"));
            return new Event(orderId, reservationNo, activityId, userId, productId, quantity);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid SECKILL_PAYMENT_EXPIRED envelope", exception);
        }
    }

    private void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw new IllegalArgumentException();
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException();
    }
    private String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > max) throw new IllegalArgumentException();
        return value.textValue();
    }
    private boolean integer(JsonNode node, int expected) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == expected;
    }
    private long positiveLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new IllegalArgumentException();
        }
        return node.longValue();
    }
    private int positiveInt(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() <= 0) {
            throw new IllegalArgumentException();
        }
        return node.intValue();
    }
    private boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private int headerAttempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-hotshop-delivery-attempt");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static DefaultRedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/expire-paid-reservation-v1.lua"));
        script.setResultType(List.class);
        return script;
    }

    private record Event(String orderId, String reservationNo, long activityId,
                         long userId, long productId, int quantity) { }
    private static final class DeterministicProjectionFailure extends RuntimeException { }
    private static final class RetryableProjectionFailure extends RuntimeException { }
}
