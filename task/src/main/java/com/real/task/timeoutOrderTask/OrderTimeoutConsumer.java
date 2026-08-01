package com.real.task.timeoutOrderTask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class OrderTimeoutConsumer {
    private static final Set<String> ENVELOPE = Set.of(
            "schemaVersion", "eventId", "eventType", "aggregateType",
            "aggregateId", "occurredAt", "payload");
    private static final Set<String> PAYLOAD = Set.of(
            "schemaVersion", "orderId", "userId", "amount", "currency",
            "expiresAtMs", "timeoutAttempt");
    private static final Pattern MONEY = Pattern.compile("^(0|[1-9][0-9]*)\\.[0-9]{2}$");
    private static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private final ObjectMapper json;
    private final OrderTimeoutService service;
    private final OrderTimeoutConsumerFailpoint failpoint;

    public OrderTimeoutConsumer(ObjectMapper json, OrderTimeoutService service,
            OrderTimeoutConsumerFailpoint failpoint) {
        this.json = json;
        this.service = service;
        this.failpoint = failpoint;
    }

    @RabbitListener(
            id = OrderTimeoutService.CONSUMER,
            queues = RabbitMQConfig.TIMEOUT_READY_QUEUE,
            ackMode = "MANUAL"
    )
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        OrderTimeoutService.TimeoutEvent event;
        try {
            event = parse(message.getBody());
            service.process(event);
        } catch (PoisonMessageException | TimeoutFactConflictException poison) {
            channel.basicReject(tag, false);
            return;
        }
        failpoint.afterCommitBeforeAck(event);
        channel.basicAck(tag, false);
    }

    OrderTimeoutService.TimeoutEvent parse(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            requireObject(root, ENVELOPE);
            require(root.path("schemaVersion").isInt() && root.path("schemaVersion").intValue() == 1);
            String eventId = requiredText(root, "eventId");
            require(validUuid(eventId));
            require("LEGACY_ORDER_TIMEOUT_REQUESTED".equals(requiredText(root, "eventType")));
            require("ORDER".equals(requiredText(root, "aggregateType")));
            String aggregateId = requiredText(root, "aggregateId");
            Instant occurredAt = Instant.parse(requiredText(root, "occurredAt"));

            JsonNode payload = root.path("payload");
            requireObject(payload, PAYLOAD);
            require(payload.path("schemaVersion").isInt() && payload.path("schemaVersion").intValue() == 1);
            String orderId = requiredText(payload, "orderId");
            require(ORDER_ID.matcher(orderId).matches());
            require(orderId.equals(aggregateId));
            require(payload.path("userId").isIntegralNumber()
                    && payload.path("userId").canConvertToLong() && payload.path("userId").longValue() > 0);
            String amountText = requiredText(payload, "amount");
            require(amountText.length() <= 20 && MONEY.matcher(amountText).matches());
            require("CNY".equals(requiredText(payload, "currency")));
            require(payload.path("expiresAtMs").isIntegralNumber()
                    && payload.path("expiresAtMs").canConvertToLong()
                    && payload.path("expiresAtMs").longValue() > 0);
            int timeoutAttempt = payload.has("timeoutAttempt") ? payload.path("timeoutAttempt").intValue() : 0;
            require(!payload.has("timeoutAttempt")
                    || (payload.path("timeoutAttempt").isInt() && timeoutAttempt >= 0));
            return new OrderTimeoutService.TimeoutEvent(eventId, "LEGACY_ORDER_TIMEOUT_REQUESTED",
                    "ORDER", aggregateId, orderId, payload.path("userId").longValue(),
                    new BigDecimal(amountText), "CNY", payload.path("expiresAtMs").longValue(),
                    timeoutAttempt, occurredAt);
        } catch (IOException | DateTimeParseException | ArithmeticException exception) {
            throw new PoisonMessageException();
        }
    }

    private static void requireObject(JsonNode node, Set<String> allowed) {
        require(node != null && node.isObject());
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        require(allowed.containsAll(actual));
    }
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank());
        return value.textValue();
    }
    private static boolean validUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return false; }
    }
    private static void require(boolean condition) { if (!condition) throw new PoisonMessageException(); }
    private static final class PoisonMessageException extends RuntimeException { }
}
