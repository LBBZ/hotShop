package com.real.task.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.real.domain.payment.MockPaymentProperties;
import com.real.domain.payment.PaymentProvider;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import com.real.common.observability.AsyncTraceContext;
import com.real.task.observability.TaskObservabilityMetrics;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

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
    private static final Set<String> CONTEXT = Set.of("requestId", "traceparent", "tracestate");
    private static final Set<String> CALLBACK = Set.of("callbackId", "paymentNo", "providerTransactionNo",
            "outcome", "amount", "currency", "occurredAt");
    private final ObjectMapper json;
    private final PaymentProvider provider;
    private final MockPaymentProperties properties;
    private final RabbitTemplate rabbit;
    private final HttpClient http;
    private final TaskObservabilityMetrics metrics;

    public MockPaymentCallbackDeliveryConsumer(ObjectMapper json, PaymentProvider provider,
            MockPaymentProperties properties, RabbitTemplate rabbit) {
        this(json, provider, properties, rabbit, null);
    }

    @Autowired
    public MockPaymentCallbackDeliveryConsumer(ObjectMapper json, PaymentProvider provider,
            MockPaymentProperties properties, RabbitTemplate rabbit,
            TaskObservabilityMetrics metrics) {
        this.json = json;
        this.provider = provider;
        this.properties = properties;
        this.rabbit = rabbit;
        this.http = HttpClient.newBuilder().connectTimeout(properties.getHttpTimeout()).build();
        this.metrics = metrics;
    }

    @RabbitListener(queues = RabbitMQConfig.MOCK_CALLBACK_QUEUE, ackMode = "MANUAL")
    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Delivery delivery;
        try { delivery = parse(message.getBody()); }
        catch (RuntimeException poison) {
            channel.basicReject(tag, false);
            rabbitMetric("consume", "reject"); rabbitMetric("consume", "dlq");
            return;
        }
        int attempt = headerAttempt(message) + 1;
        try {
            for (int i = 0; i < delivery.duplicateCount(); i++) {
                deliver(delivery.body(), message.getMessageProperties());
            }
            channel.basicAck(tag, false);
            rabbitMetric("consume", "ack");
            if (metrics != null) metrics.paymentDelivery("success");
        } catch (DeterministicRejection rejected) {
            channel.basicReject(tag, false);
            rabbitMetric("consume", "reject"); rabbitMetric("consume", "dlq");
            if (metrics != null) metrics.paymentDelivery("failure");
        } catch (RetryableDeliveryFailure retryable) {
            if (attempt >= properties.getMaxDeliveryAttempts()) {
                channel.basicReject(tag, false);
                rabbitMetric("consume", "reject"); rabbitMetric("consume", "dlq");
                if (metrics != null) metrics.paymentDelivery("failure");
                return;
            }
            MessageProperties outgoingProperties = new MessageProperties();
            outgoingProperties.setContentType("application/json");
            outgoingProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            outgoingProperties.setExpiration(Long.toString(properties.getRetryDelay().toMillis()));
            outgoingProperties.setHeader("x-hotshop-delivery-attempt", attempt);
            copyContext(message.getMessageProperties(), outgoingProperties);
            republishForRetry(new Message(message.getBody(), outgoingProperties));
            channel.basicAck(tag, false);
            rabbitMetric("retry", "published");
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

    private void deliver(byte[] body, MessageProperties incoming) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = provider.sign(timestamp, nonce, body);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(properties.getCallbackUrl())
                .timeout(properties.getHttpTimeout())
                .header("Content-Type", "application/json")
                .header("X-Mock-Timestamp", timestamp)
                .header("X-Mock-Nonce", nonce)
                .header("X-Mock-Signature", signature);
        copyHttpHeader(incoming, requestBuilder, AsyncTraceContext.REQUEST_ID, "X-Request-ID");
        copyHttpHeader(incoming, requestBuilder, AsyncTraceContext.TRACE_PARENT,
                AsyncTraceContext.TRACE_PARENT);
        copyHttpHeader(incoming, requestBuilder, AsyncTraceContext.TRACE_STATE,
                AsyncTraceContext.TRACE_STATE);
        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
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
            JsonNode payload = root.path("payload"); exactWithOptional(payload, PAYLOAD, CONTEXT);
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
    private void exactWithOptional(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw new IllegalArgumentException();
        }
    }
    private int headerAttempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-hotshop-delivery-attempt");
        return value instanceof Number number ? number.intValue() : 0;
    }
    private void rabbitMetric(String operation, String outcome) {
        if (metrics != null) metrics.rabbit(operation, outcome);
    }
    private static void copyContext(MessageProperties source, MessageProperties target) {
        for (String name : List.of(AsyncTraceContext.REQUEST_ID, AsyncTraceContext.TRACE_PARENT,
                AsyncTraceContext.TRACE_STATE)) {
            Object value = source.getHeaders().get(name);
            if (value instanceof String text && !text.isBlank() && text.length() <= 512) {
                target.setHeader(name, text);
            }
        }
    }
    private static void copyHttpHeader(MessageProperties source, HttpRequest.Builder target,
            String sourceName, String targetName) {
        Object value = source.getHeaders().get(sourceName);
        if (value instanceof String text && !text.isBlank() && text.length() <= 512) {
            target.header(targetName, text);
        }
    }
    private record Delivery(byte[] body, int duplicateCount) { }
    private static final class DeterministicRejection extends RuntimeException { }
    private static final class RetryableDeliveryFailure extends RuntimeException { }
}
