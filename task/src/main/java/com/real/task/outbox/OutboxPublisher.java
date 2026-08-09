package com.real.task.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.domain.messaging.OutboxEvent;
import com.real.domain.messaging.OutboxMapper;
import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import com.real.common.observability.AsyncTraceContext;
import com.real.task.observability.TaskObservabilityMetrics;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Set<String> BUSINESS_EVENTS = Set.of(
            "ORDER_CREATED", "RESERVATION_COMPENSATED", "ORDER_CANCELED",
            "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "PAYMENT_LATE_SUCCEEDED",
            "SECKILL_PAYMENT_EXPIRED");
    private static final String TIMEOUT_EVENT = "LEGACY_ORDER_TIMEOUT_REQUESTED";
    private static final String MOCK_CALLBACK_EVENT = "MOCK_PAYMENT_CALLBACK_REQUESTED";
    private final OutboxMapper mapper;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final OutboxPublisherProperties properties;
    private final OutboxPublishFailpoint failpoint;
    private final Clock clock;
    private final Supplier<UUID> leaseIds;
    private final TaskObservabilityMetrics metrics;
    private final Tracer tracer;

    @Autowired
    public OutboxPublisher(OutboxMapper mapper, RabbitTemplate rabbit, ObjectMapper json,
            TransactionTemplate tx, OutboxPublisherProperties properties,
            ObjectProvider<OutboxPublishFailpoint> failpoints, TaskObservabilityMetrics metrics,
            Tracer tracer) {
        this(mapper, rabbit, json, tx, properties,
                failpoints.getIfAvailable(NoOpFailpoint::new), Clock.systemUTC(), UUID::randomUUID,
                metrics, tracer);
    }

    OutboxPublisher(OutboxMapper mapper, RabbitTemplate rabbit, ObjectMapper json,
            TransactionTemplate tx, OutboxPublisherProperties properties,
            OutboxPublishFailpoint failpoint) {
        this(mapper, rabbit, json, tx, properties, failpoint, Clock.systemUTC(), UUID::randomUUID,
                null, Tracer.NOOP);
    }

    OutboxPublisher(OutboxMapper mapper, RabbitTemplate rabbit, ObjectMapper json,
            TransactionTemplate tx, OutboxPublisherProperties properties,
            OutboxPublishFailpoint failpoint, Clock clock, Supplier<UUID> leaseIds) {
        this(mapper, rabbit, json, tx, properties, failpoint, clock, leaseIds, null, Tracer.NOOP);
    }

    OutboxPublisher(OutboxMapper mapper, RabbitTemplate rabbit, ObjectMapper json,
            TransactionTemplate tx, OutboxPublisherProperties properties,
            OutboxPublishFailpoint failpoint, Clock clock, Supplier<UUID> leaseIds,
            TaskObservabilityMetrics metrics, Tracer tracer) {
        this.mapper = mapper;
        this.rabbit = rabbit;
        this.json = json;
        this.tx = tx;
        this.properties = properties;
        this.failpoint = failpoint;
        this.clock = clock;
        this.leaseIds = leaseIds;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    private static final class NoOpFailpoint implements OutboxPublishFailpoint { }

    @Scheduled(fixedDelayString = "${hotshop.outbox.publisher.poll-delay:250ms}")
    public void poll() {
        if (properties.enabled()) claimBatch().forEach(this::publish);
    }

    public List<OutboxEvent> claimBatch() {
        return Objects.requireNonNull(tx.execute(ignored -> {
            LocalDateTime now = now();
            List<OutboxEvent> candidates = mapper.lockClaimable(properties.batchSize(), now);
            List<OutboxEvent> claimed = new ArrayList<>();
            for (OutboxEvent event : candidates) {
                if ("PUBLISHING".equals(event.status())
                        && event.consecutiveAttempts() >= properties.maxAttempts()) {
                    int changed = mapper.failExpiredExhausted(event.outboxId(), event.leaseToken(),
                            event.version(), now, properties.maxAttempts());
                    if (changed == 0) throw new OutboxLeaseLostException(event.eventId());
                    continue;
                }
                String token = leaseIds.get().toString();
                int changed = mapper.claim(event.outboxId(), event.version(), token, now,
                        now.plus(properties.lease()), properties.maxAttempts());
                if (changed == 1) claimed.add(mapper.find(event.outboxId()));
                else throw new OutboxLeaseLostException(event.eventId());
            }
            return claimed;
        }));
    }

    public void publish(OutboxEvent event) {
        long started = System.nanoTime();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        Span span = startObservationSpan(event);
        Tracer.SpanInScope scope = openScope(span);
        try {
            publishInternal(event, span, scope != null);
        } catch (RuntimeException failure) {
            observeError(span, failure);
            throw failure;
        } finally {
            closeScope(scope);
            String outcome = observationOutcome(event);
            endObservationSpan(span, outcome);
            recordObservationMetric(outcome, started);
            restoreMdc(previousMdc);
        }
    }

    private Span startObservationSpan(OutboxEvent event) {
        try {
            TraceContext parent = traceParent(event);
            return (parent == null ? tracer.spanBuilder().setNoParent()
                    : tracer.spanBuilder().setParent(parent))
                    .name("outbox.publish")
                    .kind(Span.Kind.PRODUCER)
                    .tag("messaging.system", "rabbitmq")
                    .tag("messaging.operation", "publish")
                    .tag("event.type", event.eventType())
                    .start();
        } catch (RuntimeException failure) {
            observationFailure("span_start");
            return null;
        }
    }

    private Tracer.SpanInScope openScope(Span span) {
        if (span == null) return null;
        try {
            return tracer.withSpan(span);
        } catch (RuntimeException failure) {
            observationFailure("scope_open");
            return null;
        }
    }

    private void observeError(Span span, RuntimeException failure) {
        if (span == null) return;
        try {
            span.error(failure);
        } catch (RuntimeException observationFailure) {
            observationFailure("span_error");
        }
    }

    private void closeScope(Tracer.SpanInScope scope) {
        if (scope == null) return;
        try {
            scope.close();
        } catch (RuntimeException failure) {
            observationFailure("scope_close");
        }
    }

    private String observationOutcome(OutboxEvent event) {
        try {
            OutboxEvent current = mapper.find(event.outboxId());
            return current == null ? "missing" : current.status().toLowerCase(Locale.ROOT);
        } catch (RuntimeException failure) {
            observationFailure("outcome_read");
            return "unknown";
        }
    }

    private void endObservationSpan(Span span, String outcome) {
        if (span == null) return;
        try {
            span.tag("outcome", outcome).end();
        } catch (RuntimeException failure) {
            observationFailure("span_end");
        }
    }

    private void recordObservationMetric(String outcome, long started) {
        if (metrics == null) return;
        try {
            metrics.outbox(outcome, Duration.ofNanos(System.nanoTime() - started));
        } catch (RuntimeException failure) {
            observationFailure("metric_record");
        }
    }

    private static void restoreMdc(Map<String, String> previous) {
        MDC.clear();
        if (previous != null) MDC.setContextMap(previous);
    }

    private static void observationFailure(String stage) {
        log.warn("Outbox observation failure isolated stage={}", stage);
    }

    private void publishInternal(OutboxEvent event, Span publisherSpan,
            boolean publisherScopeOpened) {
        failpoint.afterClaim(event);
        Route route;
        try {
            route = route(event);
        } catch (UnknownEventType exception) {
            recordFailure(event, "UNKNOWN_EVENT_TYPE", false);
            return;
        } catch (InvalidPayload exception) {
            recordFailure(event, "INVALID_PAYLOAD", false);
            return;
        } catch (InvalidRoute exception) {
            recordFailure(event, "INVALID_ROUTE", false);
            return;
        }

        try {
            CarrierContext carrier = carrierContext(
                    route.payload(), publisherSpan, publisherScopeOpened);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schemaVersion", 1);
            envelope.put("eventId", event.eventId());
            envelope.put("eventType", event.eventType());
            envelope.put("aggregateType", event.aggregateType());
            envelope.put("aggregateId", event.aggregateId());
            envelope.put("occurredAt", event.createdAt().toInstant(ZoneOffset.UTC).toString());
            envelope.put("payload", route.payload());

            CorrelationData correlation = new CorrelationData(event.eventId());
            rabbit.convertAndSend(route.exchange(), route.routingKey(), envelope, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                copyContextHeader(route.payload(), message.getMessageProperties(),
                        "requestId", AsyncTraceContext.REQUEST_ID);
                if (!carrier.traceparent().isBlank()) {
                    message.getMessageProperties().setHeader(
                            AsyncTraceContext.TRACE_PARENT, carrier.traceparent()
                    );
                }
                if (!carrier.tracestate().isBlank()) {
                    message.getMessageProperties().setHeader(
                            AsyncTraceContext.TRACE_STATE, carrier.tracestate());
                }
                if (route.expirationMs() > 0) {
                    message.getMessageProperties().setExpiration(Long.toString(route.expirationMs()));
                }
                return message;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                rabbitMetric("publish", "nack");
                recordFailure(event, "BROKER_NACK", true);
                return;
            }
            if (correlation.getReturned() != null) {
                rabbitMetric("publish", "return");
                recordFailure(event, "UNROUTABLE", true);
                return;
            }
            failpoint.afterBrokerConfirm(event);
            markPublished(event);
            rabbitMetric("publish", "ack");
        } catch (OutboxPublisherCrashException crash) {
            throw crash;
        } catch (OutboxLeaseLostException leaseLost) {
            throw leaseLost;
        } catch (TimeoutException exception) {
            recordFailure(event, "CONFIRM_TIMEOUT", true);
        } catch (AmqpException exception) {
            recordFailure(event, "CONNECTION_FAILURE", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(event, "CONNECTION_FAILURE", true);
        } catch (Exception exception) {
            recordFailure(event, "CONNECTION_FAILURE", true);
        }
    }

    private CarrierContext carrierContext(JsonNode payload, Span publisherSpan,
            boolean publisherScopeOpened) {
        String persistedTraceParent = payload.path(AsyncTraceContext.TRACE_PARENT).asText("");
        if (!AsyncTraceContext.parse(persistedTraceParent).valid()) {
            persistedTraceParent = "";
        }
        String persistedTraceState = AsyncTraceContext.sanitizeTraceState(
                payload.path(AsyncTraceContext.TRACE_STATE).asText(""));
        if (!publisherScopeOpened || publisherSpan == null) {
            return new CarrierContext(persistedTraceParent, persistedTraceState);
        }
        try {
            TraceContext context = publisherSpan.context();
            String publisherTraceParent = "00-" + context.traceId() + "-" + context.spanId()
                    + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00");
            if (AsyncTraceContext.parse(publisherTraceParent).valid()) {
                return new CarrierContext(publisherTraceParent, persistedTraceState);
            }
        } catch (RuntimeException observationFailure) {
            observationFailure("carrier_read");
        }
        return new CarrierContext(persistedTraceParent, persistedTraceState);
    }

    private TraceContext traceParent(OutboxEvent event) {
        try {
            AsyncTraceContext.Parsed parsed = AsyncTraceContext.parse(
                    json.readTree(event.payload()).path("traceparent").asText("")
            );
            if (!parsed.valid()) return null;
            return tracer.traceContextBuilder().traceId(parsed.traceId())
                    .spanId(parsed.parentSpanId())
                    .sampled((Integer.parseInt(parsed.flags(), 16) & 1) == 1)
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Route route(OutboxEvent event) {
        if (!BUSINESS_EVENTS.contains(event.eventType()) && !TIMEOUT_EVENT.equals(event.eventType())
                && !MOCK_CALLBACK_EVENT.equals(event.eventType())) {
            throw new UnknownEventType();
        }
        if (!validUuid(event.eventId()) || event.aggregateType() == null
                || event.aggregateType().isBlank() || event.aggregateId() == null
                || event.aggregateId().isBlank()) {
            throw new InvalidRoute();
        }
        JsonNode payload;
        try {
            payload = json.readTree(event.payload());
        } catch (JsonProcessingException exception) {
            throw new InvalidPayload();
        }
        if (payload == null || !payload.isObject()) throw new InvalidPayload();
        if (BUSINESS_EVENTS.contains(event.eventType())) {
            return new Route(RabbitMQConfig.BUSINESS_EXCHANGE, event.eventType(), 0, payload);
        }
        if (MOCK_CALLBACK_EVENT.equals(event.eventType())) {
            if (!"PAYMENT".equals(event.aggregateType())
                    || !event.aggregateId().equals(payload.path("callback").path("paymentNo").asText())
                    || payload.path("duplicateCount").asInt(0) < 1) {
                throw new InvalidPayload();
            }
            return new Route(RabbitMQConfig.MOCK_CALLBACK_EXCHANGE,
                    RabbitMQConfig.MOCK_CALLBACK_ROUTING_KEY, 0, payload);
        }
        if (!"ORDER".equals(event.aggregateType())) {
            throw new InvalidRoute();
        }
        if (payload.path("expiresAtMs").asLong(0) <= 0
                || payload.path("orderId").asText("").isBlank()
                || !event.aggregateId().equals(payload.path("orderId").asText())) {
            throw new InvalidPayload();
        }
        long remaining = Math.max(0, payload.path("expiresAtMs").asLong() - clock.millis());
        return new Route(remaining == 0 ? RabbitMQConfig.TIMEOUT_READY_EXCHANGE
                        : RabbitMQConfig.TIMEOUT_SCHEDULE_EXCHANGE,
                RabbitMQConfig.TIMEOUT_ROUTING_KEY, remaining, payload);
    }

    private void markPublished(OutboxEvent event) {
        tx.executeWithoutResult(ignored -> {
            if (mapper.published(event.outboxId(), event.leaseToken(), event.version(), now()) == 0) {
                throw new OutboxLeaseLostException(event.eventId());
            }
        });
    }

    private void recordFailure(OutboxEvent event, String category, boolean retryable) {
        boolean terminal = !retryable || event.consecutiveAttempts() >= properties.maxAttempts();
        long exponent = Math.max(0, event.consecutiveAttempts() - 1L);
        double scaled = properties.initialBackoff().toMillis()
                * Math.pow(properties.multiplier(), exponent);
        long delay = Math.min(properties.maxBackoff().toMillis(),
                scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) scaled);
        LocalDateTime availableAt = now().plusNanos(delay * 1_000_000);
        tx.executeWithoutResult(ignored -> {
            int changed = mapper.recordFailure(event.outboxId(), event.leaseToken(), event.version(),
                    terminal ? "FAILED" : "NEW", availableAt, category);
            if (changed == 0) throw new OutboxLeaseLostException(event.eventId());
        });
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private void rabbitMetric(String operation, String outcome) {
        if (metrics == null) return;
        try {
            metrics.rabbit(operation, outcome);
        } catch (RuntimeException failure) {
            observationFailure("rabbit_metric");
        }
    }

    private static void copyContextHeader(JsonNode payload,
            org.springframework.amqp.core.MessageProperties properties,
            String payloadField, String header) {
        String value = payload.path(payloadField).asText("");
        if (!value.isBlank() && value.length() <= 512) {
            properties.setHeader(header, value);
        }
    }

    private static boolean validUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private record Route(String exchange, String routingKey, long expirationMs, JsonNode payload) { }
    private record CarrierContext(String traceparent, String tracestate) { }
    private static final class UnknownEventType extends RuntimeException { }
    private static final class InvalidPayload extends RuntimeException { }
    private static final class InvalidRoute extends RuntimeException { }
}
