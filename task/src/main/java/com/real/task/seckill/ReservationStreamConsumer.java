package com.real.task.seckill;

import com.real.common.observability.AsyncTraceContext;
import com.real.infrastructure.redis.SeckillRedisKeys;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(
        prefix = "hotshop.seckill.order-consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationStreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(ReservationStreamConsumer.class);
    private static final byte[] ZERO_ID = bytes("0-0");

    private final StringRedisTemplate redis;
    private final SeckillOrderProperties properties;
    private final SeckillRedisReservationGateway reservationGateway;
    private final SeckillProcessingService processingService;
    private final SeckillProcessingFailpoint failpoint;
    private final SeckillOrderMetrics metrics;
    private final String consumerName;
    private final Tracer tracer;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final Map<String, String> claimCursors = new HashMap<>();
    private volatile List<String> streams = List.of();
    private volatile long nextDiscoveryAt;

    public ReservationStreamConsumer(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis,
            SeckillOrderProperties properties,
            SeckillRedisReservationGateway reservationGateway,
            SeckillProcessingService processingService,
            SeckillProcessingFailpoint failpoint,
            SeckillOrderMetrics metrics
    ) {
        this(redis, properties, reservationGateway, processingService, failpoint, metrics, Tracer.NOOP);
    }

    public ReservationStreamConsumer(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis,
            SeckillOrderProperties properties,
            SeckillRedisReservationGateway reservationGateway,
            SeckillProcessingService processingService,
            SeckillProcessingFailpoint failpoint,
            SeckillOrderMetrics metrics,
            Tracer tracer
    ) {
        this.redis = redis;
        this.properties = properties;
        this.reservationGateway = reservationGateway;
        this.processingService = processingService;
        this.failpoint = failpoint;
        this.metrics = metrics;
        this.tracer = tracer;
        this.consumerName = uniqueConsumerName(properties.getConsumerPrefix());
    }

    @Autowired
    public ReservationStreamConsumer(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis,
            SeckillOrderProperties properties,
            SeckillRedisReservationGateway reservationGateway,
            SeckillProcessingService processingService,
            ObjectProvider<SeckillProcessingFailpoint> failpoints,
            SeckillOrderMetrics metrics,
            Tracer tracer
    ) {
        this(redis, properties, reservationGateway, processingService,
                failpoints.getIfAvailable(NoOpSeckillProcessingFailpoint::new), metrics, tracer);
    }

    @Scheduled(fixedDelayString = "${hotshop.seckill.order-consumer.poll-delay:250ms}")
    public void poll() {
        try {
            refreshStreamsIfDue();
            List<String> snapshot = streams;
            if (snapshot.isEmpty()) {
                metrics.pending(0, 0);
                return;
            }
            int start = Math.floorMod(roundRobin.getAndIncrement(), snapshot.size());
            boolean consumedAny = false;
            long pendingCount = 0;
            long oldestIdle = 0;
            long lag = 0;
            for (int offset = 0; offset < snapshot.size(); offset++) {
                String stream = snapshot.get((start + offset) % snapshot.size());
                PendingSummary pending = pendingSummary(stream);
                pendingCount += pending.count();
                oldestIdle = Math.max(oldestIdle, pending.oldestIdleMs());
                lag += groupLag(stream);
                if (pending.count() > 0) {
                    consumedAny |= claimOnePage(stream);
                }
                consumedAny |= readNew(stream, false);
            }
            metrics.pending(pendingCount, oldestIdle);
            metrics.streamLag(lag);
            if (!consumedAny) {
                readNew(snapshot.get(start), true);
            }
        } catch (DataAccessException exception) {
            metrics.failures().increment();
            Throwable cause = exception.getMostSpecificCause();
            log.warn("Seckill Stream poll deferred; category={}, cause={}",
                    exception.getClass().getSimpleName(), cause.getClass().getSimpleName(), exception);
        } catch (RuntimeException exception) {
            metrics.failures().increment();
            log.error("Seckill Stream poll failed with category {}",
                    exception.getClass().getSimpleName(), exception);
        }
    }

    public void refreshStreams() {
        Set<String> registered = redis.opsForSet().members(SeckillRedisKeys.reservationStreamRegistry());
        if (registered == null) {
            registered = Set.of();
        }
        List<String> discovered = registered.stream()
                .filter(key -> SeckillRedisKeys.activityIdFromReservationStream(key) != null)
                .sorted(Comparator.naturalOrder())
                .toList();
        for (String stream : discovered) {
            ensureGroup(stream);
        }
        streams = discovered;
        claimCursors.keySet().retainAll(discovered);
        nextDiscoveryAt = System.currentTimeMillis() + properties.getDiscoveryInterval().toMillis();
    }

    public List<String> discoveredStreams() {
        return streams;
    }

    public String consumerName() {
        return consumerName;
    }

    private void refreshStreamsIfDue() {
        if (System.currentTimeMillis() >= nextDiscoveryAt) {
            refreshStreams();
        }
    }

    private boolean readNew(String stream, boolean block) {
        StreamReadOptions options = StreamReadOptions.empty().count(properties.getReadBatch());
        if (block) {
            options = options.block(properties.getReadBlock());
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(properties.getGroupName(), consumerName),
                options,
                StreamOffset.create(stream, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return false;
        }
        records.forEach(record -> process(stream, record.getId().getValue(), record.getValue(), false));
        return true;
    }

    private boolean claimOnePage(String stream) {
        String cursor = claimCursors.getOrDefault(stream, "0-0");
        AutoClaimPage page = redis.execute((RedisCallback<AutoClaimPage>) connection ->
                autoClaim(connection, stream, cursor)
        );
        if (page == null) {
            return false;
        }
        claimCursors.put(stream, page.nextCursor());
        for (ClaimedRecord record : page.records()) {
            metrics.claimed().increment();
            if (record.values().containsKey("eventId")
                    && !processingService.retryDue(String.valueOf(record.values().get("eventId")))) {
                continue;
            }
            process(stream, record.entryId(), record.values(), true);
        }
        return !page.records().isEmpty();
    }

    void process(
            String stream,
            String entryId,
            Map<Object, Object> values,
            boolean claimed
    ) {
        metrics.consumed().increment();
        ReservationAcceptedEvent.ParseResult parsed =
                ReservationAcceptedEvent.parse(stream, entryId, values);
        if (!parsed.valid()) {
            processingService.quarantineInvalid(stream, entryId, parsed);
            metrics.quarantined().increment();
            acknowledge(stream, entryId);
            return;
        }

        ReservationAcceptedEvent event = parsed.event();
        AsyncTraceContext.Parsed parent = AsyncTraceContext.parse(event.traceparent());
        String previousRequestId = MDC.get("requestId");
        String previousTraceId = MDC.get("traceId");
        String previousSpanId = MDC.get("spanId");
        String previousTraceState = MDC.get(AsyncTraceContext.TRACE_STATE);
        MDC.put("requestId", event.requestId());
        if (event.tracestate().isBlank()) {
            MDC.remove(AsyncTraceContext.TRACE_STATE);
        } else {
            MDC.put(AsyncTraceContext.TRACE_STATE, event.tracestate());
        }
        if (parent.valid()) {
            MDC.put("traceId", parent.traceId());
            MDC.put("spanId", parent.parentSpanId());
        } else {
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
        Span span = startObservationSpan(parent, claimed);
        Timer.Sample sample = startObservationTimer();
        Tracer.SpanInScope scope = openObservationScope(span);
        try {
            SeckillRedisReservationGateway.ReservationProof proof =
                    reservationGateway.verify(event);
            if (!proof.valid()) {
                terminalManual(stream, entryId, event, proof.reasonCode());
                return;
            }
            if (!Set.of("RESERVED", "ORDER_CREATED", "COMPENSATING", "COMPENSATED")
                    .contains(proof.status())) {
                terminalManual(stream, entryId, event, "REDIS_RESERVATION_STATUS_INVALID");
                return;
            }

            SeckillProcessingService.ProcessOutcome outcome;
            if ("ORDER_CREATED".equals(proof.status())) {
                outcome = processingService.mergeCommittedOrder(
                        stream,
                        entryId,
                        event
                );
            } else if (!"RESERVED".equals(proof.status())) {
                outcome = processingService.knownTerminalOutcome(event);
                if (outcome == null) {
                    terminalManual(
                            stream,
                            entryId,
                            event,
                            "REDIS_TERMINAL_WITHOUT_MYSQL_EVIDENCE"
                    );
                    return;
                }
            } else {
                outcome = processingService.createOrder(stream, entryId, event);
            }
            switch (outcome.type()) {
                case ORDER_CREATED -> {
                    failpoint.afterOrderCommitBeforeFinalize(event);
                    finalizeAndAck(stream, entryId, event, outcome.orderId(), false);
                    metrics.processed().increment();
                }
                case DUPLICATE_ORDER -> {
                    finalizeAndAck(stream, entryId, event, outcome.orderId(), true);
                    metrics.duplicate().increment();
                }
                case COMPENSATING ->
                        compensateAndAck(
                                stream,
                                entryId,
                                event,
                                outcome.compensationId(),
                                outcome.reasonCode()
                        );
                case COMPENSATED -> {
                    SeckillRedisReservationGateway.CompensationResult result =
                            compensate(
                                    event,
                                    outcome.compensationId(),
                                    outcome.reasonCode()
                            );
                    if (!result.successful()) {
                        terminalManual(
                                stream,
                                entryId,
                                event,
                                "COMPENSATION_" + result.code().name()
                        );
                        return;
                    }
                    acknowledge(stream, entryId);
                    metrics.duplicate().increment();
                }
                case MANUAL_REVIEW -> {
                    metrics.manualReview().increment();
                    acknowledge(stream, entryId);
                }
                case RETRY_NOT_DUE -> metrics.retried().increment();
            }
        } catch (SeckillProcessingService.SafeInventoryFailure exception) {
            observeError(span, exception);
            handleFailure(
                    stream,
                    entryId,
                    event,
                    SeckillProcessingService.FailureKind.SAFE_INVENTORY,
                    exception.getMessage()
            );
        } catch (SeckillProcessingService.ManualFactFailure exception) {
            observeError(span, exception);
            handleFailure(
                    stream,
                    entryId,
                    event,
                    SeckillProcessingService.FailureKind.MANUAL,
                    exception.getMessage()
            );
        } catch (DataAccessException exception) {
            observeError(span, exception);
            recordTransientBestEffort(stream, entryId, event, "DEPENDENCY_UNAVAILABLE");
            metrics.failures().increment();
        } catch (RuntimeException exception) {
            observeError(span, exception);
            recordTransientBestEffort(stream, entryId, event, "UNEXPECTED_PROCESSING_FAILURE");
            metrics.failures().increment();
        } finally {
            closeObservationScope(scope);
            endObservationSpan(span);
            stopObservationTimer(sample);
            restoreMdc("requestId", previousRequestId);
            restoreMdc("traceId", previousTraceId);
            restoreMdc("spanId", previousSpanId);
            restoreMdc(AsyncTraceContext.TRACE_STATE, previousTraceState);
        }
    }

    private Span startObservationSpan(AsyncTraceContext.Parsed parent, boolean claimed) {
        try {
            TraceContext remote = parent.valid()
                    ? tracer.traceContextBuilder().traceId(parent.traceId())
                            .spanId(parent.parentSpanId())
                            .sampled((Integer.parseInt(parent.flags(), 16) & 1) == 1)
                            .build()
                    : null;
            Span.Builder builder = remote == null
                    ? tracer.spanBuilder().setNoParent()
                    : claimed
                            ? tracer.spanBuilder().setNoParent().addLink(
                                    new Link(remote, Map.of("messaging.redelivery", true))
                            )
                            : tracer.spanBuilder().setParent(remote);
            return builder.name("redis.stream.consume")
                    .kind(Span.Kind.CONSUMER)
                    .tag("messaging.system", "redis")
                    .tag("messaging.operation", claimed ? "claim" : "receive")
                    .start();
        } catch (RuntimeException failure) {
            observationFailure("span_start");
            return null;
        }
    }

    private Timer.Sample startObservationTimer() {
        try {
            return Timer.start();
        } catch (RuntimeException failure) {
            observationFailure("timer_start");
            return null;
        }
    }

    private Tracer.SpanInScope openObservationScope(Span span) {
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

    private void closeObservationScope(Tracer.SpanInScope scope) {
        if (scope == null) return;
        try {
            scope.close();
        } catch (RuntimeException failure) {
            observationFailure("scope_close");
        }
    }

    private void endObservationSpan(Span span) {
        if (span == null) return;
        try {
            span.end();
        } catch (RuntimeException failure) {
            observationFailure("span_end");
        }
    }

    private void stopObservationTimer(Timer.Sample sample) {
        if (sample == null) return;
        try {
            sample.stop(metrics.conversionLatency());
        } catch (RuntimeException failure) {
            observationFailure("timer_stop");
        }
    }

    private static void observationFailure(String stage) {
        log.warn("Seckill consumer observation failure isolated stage={}", stage);
    }

    private long groupLag(String stream) {
        return redis.opsForStream().groups(stream).stream()
                .filter(group -> properties.getGroupName().equals(group.groupName()))
                .findFirst()
                .map(group -> redis.opsForStream().range(
                        stream,
                        Range.rightUnbounded(Range.Bound.exclusive(group.lastDeliveredId())),
                        Limit.limit().count(10_001)
                ))
                .map(List::size)
                .orElse(0);
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private void handleFailure(
            String stream,
            String entryId,
            ReservationAcceptedEvent event,
            SeckillProcessingService.FailureKind kind,
            String reasonCode
    ) {
        SeckillProcessingService.FailureOutcome failure = processingService.recordFailure(
                stream,
                entryId,
                event,
                kind,
                safeReason(reasonCode)
        );
        switch (failure.type()) {
            case RETRYING -> metrics.retried().increment();
            case COMPENSATING -> {
                failpoint.afterCompensationIntent(event);
                compensateAndAck(
                        stream,
                        entryId,
                        event,
                        failure.compensationId(),
                        failure.reasonCode()
                );
            }
            case COMPENSATED -> compensateAndAck(
                    stream,
                    entryId,
                    event,
                    failure.compensationId(),
                    failure.reasonCode()
            );
            case ORDER_CREATED ->
                    finalizeAndAck(stream, entryId, event, failure.orderId(), true);
            case MANUAL_REVIEW -> {
                metrics.manualReview().increment();
                acknowledge(stream, entryId);
            }
        }
    }

    private void compensateAndAck(
            String stream,
            String entryId,
            ReservationAcceptedEvent event,
            String compensationId,
            String reasonCode
    ) {
        SeckillRedisReservationGateway.CompensationResult compensation =
                compensate(event, compensationId, reasonCode);
        if (!compensation.successful()) {
            if (compensation.code()
                    == SeckillRedisReservationGateway.CompensationCode.STORAGE_ERROR) {
                recordTransientBestEffort(
                        stream,
                        entryId,
                        event,
                        "REDIS_COMPENSATION_STORAGE_FAILURE"
                );
                return;
            }
            terminalManual(
                    stream,
                    entryId,
                    event,
                    "COMPENSATION_" + compensation.code().name()
            );
            return;
        }
        failpoint.afterRedisCompensation(event);
        processingService.finishCompensation(event, compensationId, reasonCode);
        acknowledge(stream, entryId);
        metrics.compensated().increment();
    }

    private SeckillRedisReservationGateway.CompensationResult compensate(
            ReservationAcceptedEvent event,
            String compensationId,
            String reasonCode
    ) {
        try {
            SeckillRedisReservationGateway.CompensationResult result =
                    reservationGateway.compensate(event, compensationId, reasonCode);
            metrics.inventory("compensation", result.successful() ? "success" : "failure");
            return result;
        } catch (RuntimeException failure) {
            metrics.inventory("compensation", "failure");
            throw failure;
        }
    }

    private void finalizeAndAck(
            String stream,
            String entryId,
            ReservationAcceptedEvent event,
            String orderId,
            boolean duplicate
    ) {
        SeckillRedisReservationGateway.FinalizeResult result =
                reservationGateway.finalizeOrder(event, orderId);
        if (!result.successful()) {
            terminalManual(
                    stream,
                    entryId,
                    event,
                    "FINALIZE_" + result.code().name()
            );
            return;
        }
        failpoint.afterFinalizeBeforeAck(event);
        acknowledge(stream, entryId);
    }

    private void terminalManual(
            String stream,
            String entryId,
            ReservationAcceptedEvent event,
            String reasonCode
    ) {
        processingService.markManual(stream, entryId, event, safeReason(reasonCode));
        metrics.manualReview().increment();
        acknowledge(stream, entryId);
    }

    private void recordTransientBestEffort(
            String stream,
            String entryId,
            ReservationAcceptedEvent event,
            String reasonCode
    ) {
        try {
            processingService.recordFailure(
                    stream,
                    entryId,
                    event,
                    SeckillProcessingService.FailureKind.TRANSIENT,
                    reasonCode
            );
            metrics.retried().increment();
        } catch (RuntimeException ignored) {
            // MySQL can be the unavailable dependency. Pending remains the source of recovery.
        }
    }

    private void acknowledge(String stream, String entryId) {
        Long acknowledged = redis.opsForStream().acknowledge(
                stream,
                properties.getGroupName(),
                entryId
        );
        if (acknowledged == null || acknowledged != 1L) {
            throw new IllegalStateException("Stream entry was not acknowledged");
        }
    }

    private void ensureGroup(String stream) {
        try {
            redis.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(stream),
                    bytes(properties.getGroupName()),
                    ZERO_ID,
                    bytes("MKSTREAM")
            ));
        } catch (DataAccessException exception) {
            Throwable cause = exception.getMostSpecificCause();
            String message = cause == null ? null : cause.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    private PendingSummary pendingSummary(String stream) {
        PendingMessagesSummary summary =
                redis.opsForStream().pending(stream, properties.getGroupName());
        long count = summary == null ? 0 : summary.getTotalPendingMessages();
        if (count == 0) {
            return new PendingSummary(0, 0);
        }
        PendingMessages details = redis.opsForStream().pending(
                stream,
                properties.getGroupName(),
                Range.unbounded(),
                Math.min(count, Math.max(1, properties.getClaimBatch()))
        );
        long oldestIdle = 0;
        if (details != null) {
            for (PendingMessage pending : details) {
                oldestIdle = Math.max(
                        oldestIdle,
                        pending.getElapsedTimeSinceLastDelivery().toMillis()
                );
                pending.getTotalDeliveryCount();
            }
        }
        return new PendingSummary(count, oldestIdle);
    }

    @SuppressWarnings("unchecked")
    private AutoClaimPage autoClaim(
            RedisConnection connection,
            String stream,
            String cursor
    ) {
        RedisStreamAsyncCommands<byte[], byte[]> commands = streamCommands(connection);
        XAutoClaimArgs<byte[]> args = new XAutoClaimArgs<byte[]>()
                .consumer(io.lettuce.core.Consumer.from(
                        bytes(properties.getGroupName()),
                        bytes(consumerName)
                ))
                .minIdleTime(properties.getClaimIdle())
                .startId(cursor)
                .count(properties.getClaimBatch());
        ClaimedMessages<byte[], byte[]> claimed;
        try {
            claimed = commands.xautoclaim(bytes(stream), args).get(
                    Math.max(1_000, properties.getReadBlock().toMillis() + 1_000),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Redis claim was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Redis claim did not complete", exception);
        }
        List<ClaimedRecord> records = new ArrayList<>();
        for (StreamMessage<byte[], byte[]> message : claimed.getMessages()) {
            Map<Object, Object> values = new LinkedHashMap<>();
            for (Map.Entry<byte[], byte[]> field : message.getBody().entrySet()) {
                values.put(text(field.getKey()), text(field.getValue()));
            }
            records.add(new ClaimedRecord(message.getId(), values));
        }
        String next = claimed.getId();
        return new AutoClaimPage(next == null || next.isBlank() ? "0-0" : next, records);
    }

    @SuppressWarnings("unchecked")
    private RedisStreamAsyncCommands<byte[], byte[]> streamCommands(RedisConnection connection) {
        Object nativeConnection = connection.getNativeConnection();
        if (nativeConnection instanceof RedisStreamAsyncCommands<?, ?> async) {
            return (RedisStreamAsyncCommands<byte[], byte[]>) async;
        }
        if (nativeConnection instanceof StatefulRedisConnection<?, ?> stateful) {
            return (RedisStreamAsyncCommands<byte[], byte[]>) stateful.async();
        }
        throw new IllegalStateException("Redis driver does not expose Stream commands");
    }

    private static String uniqueConsumerName(String configuredPrefix) {
        String prefix = configuredPrefix == null
                ? "order"
                : configuredPrefix.replaceAll("[^A-Za-z0-9_-]", "-");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName()
                    .replaceAll("[^A-Za-z0-9_-]", "-");
        } catch (Exception exception) {
            host = "unknown-host";
        }
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        return prefix + "-" + host + "-" + ProcessHandle.current().pid() + "-"
                + HexFormat.of().formatHex(random);
    }

    private static String safeReason(String value) {
        if (value == null || !value.matches("^[A-Z0-9_]{1,64}$")) {
            return "CLASSIFIED_PROCESSING_FAILURE";
        }
        return value;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value == null ? "" : value.toString();
    }

    private record ClaimedRecord(String entryId, Map<Object, Object> values) {
    }

    private record AutoClaimPage(String nextCursor, List<ClaimedRecord> records) {
    }

    private record PendingSummary(long count, long oldestIdleMs) {
    }
}
