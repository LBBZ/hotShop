package com.real.task.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.real.domain.payment.MockPaymentProperties;
import com.real.domain.payment.PaymentProvider;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class MockPaymentCallbackDeliveryConsumer {
    private static final Set<String> ENVELOPE = Set.of("schemaVersion", "eventId", "eventType",
            "aggregateType", "aggregateId", "occurredAt", "payload");
    private static final Set<String> PAYLOAD = Set.of("schemaVersion", "callback", "duplicateCount");
    private static final Set<String> CALLBACK = Set.of("callbackId", "paymentNo", "providerTransactionNo",
            "outcome", "amount", "currency", "occurredAt");
    private final ObjectMapper json;
    private final PaymentProvider provider;
    private final MockPaymentProperties properties;
    private final RabbitTemplate rabbit;
    private final HttpClient http;

    public MockPaymentCallbackDeliveryConsumer(ObjectMapper json, PaymentProvider provider,
            MockPaymentProperties properties, RabbitTemplate rabbit) {
        this.json = json;
        this.provider = provider;
        this.properties = properties;
        this.rabbit = rabbit;
        this.http = HttpClient.newBuilder().connectTimeout(properties.getHttpTimeout()).build();
    }

    @RabbitListener(queues = RabbitMQConfig.MOCK_CALLBACK_QUEUE, ackMode = "MANUAL")
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Delivery delivery;
        try { delivery = parse(message.getBody()); }
        catch (RuntimeException poison) { channel.basicReject(tag, false); return; }
        int attempt = headerAttempt(message) + 1;
        try {
            for (int i = 0; i < delivery.duplicateCount(); i++) deliver(delivery.body());
            channel.basicAck(tag, false);
        } catch (DeterministicRejection rejected) {
            channel.basicReject(tag, false);
        } catch (RetryableDeliveryFailure retryable) {
            if (attempt >= properties.getMaxDeliveryAttempts()) {
                channel.basicReject(tag, false);
                return;
            }
            MessageProperties outgoingProperties = new MessageProperties();
            outgoingProperties.setContentType("application/json");
            outgoingProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            outgoingProperties.setExpiration(Long.toString(properties.getRetryDelay().toMillis()));
            outgoingProperties.setHeader("x-hotshop-delivery-attempt", attempt);
            republishForRetry(new Message(message.getBody(), outgoingProperties));
            channel.basicAck(tag, false);
        }
    }

    private void republishForRetry(Message message) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(RabbitMQConfig.MOCK_CALLBACK_RETRY_EXCHANGE,
                RabbitMQConfig.MOCK_CALLBACK_ROUTING_KEY, message, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getHttpTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new RetryableDeliveryFailure();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableDeliveryFailure();
        } catch (Exception exception) {
            throw new RetryableDeliveryFailure();
        }
    }

    private void deliver(byte[] body) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = provider.sign(timestamp, nonce, body);
        HttpRequest request = HttpRequest.newBuilder(properties.getCallbackUrl())
                .timeout(properties.getHttpTimeout())
                .header("Content-Type", "application/json")
                .header("X-Mock-Timestamp", timestamp)
                .header("X-Mock-Nonce", nonce)
                .header("X-Mock-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return;
            if (response.statusCode() >= 400 && response.statusCode() < 500) throw new DeterministicRejection();
            throw new RetryableDeliveryFailure();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableDeliveryFailure();
        } catch (ConnectException exception) {
            throw new RetryableDeliveryFailure();
        } catch (IOException exception) {
            throw new RetryableDeliveryFailure();
        }
    }

    private Delivery parse(byte[] bytes) {
        try {
            JsonNode root = json.readTree(bytes);
            exact(root, ENVELOPE);
            if (root.path("schemaVersion").asInt() != 1
                    || !"MOCK_PAYMENT_CALLBACK_REQUESTED".equals(root.path("eventType").asText())
                    || !"PAYMENT".equals(root.path("aggregateType").asText())) throw new IllegalArgumentException();
            JsonNode payload = root.path("payload"); exact(payload, PAYLOAD);
            JsonNode callback = payload.path("callback"); exact(callback, CALLBACK);
            if (!root.path("aggregateId").asText().equals(callback.path("paymentNo").asText())) throw new IllegalArgumentException();
            int count = payload.path("duplicateCount").intValue();
            if (count < 1 || count > properties.getMaxDuplicateCount()) throw new IllegalArgumentException();
            return new Delivery(json.writeValueAsBytes(callback), count);
        } catch (Exception exception) { throw new IllegalArgumentException(); }
    }

    private void exact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw new IllegalArgumentException();
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException();
    }
    private int headerAttempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-hotshop-delivery-attempt");
        return value instanceof Number number ? number.intValue() : 0;
    }
    private record Delivery(byte[] body, int duplicateCount) { }
    private static final class DeterministicRejection extends RuntimeException { }
    private static final class RetryableDeliveryFailure extends RuntimeException { }
}
