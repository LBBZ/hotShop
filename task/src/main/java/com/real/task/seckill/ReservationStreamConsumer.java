package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.api.async.RedisStreamAsyncCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
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
        this.redis = redis;
        this.properties = properties;
        this.reservationGateway = reservationGateway;
        this.processingService = processingService;
        this.failpoint = failpoint;
        this.metrics = metrics;
        this.consumerName = uniqueConsumerName(properties.getConsumerPrefix());
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
            for (int offset = 0; offset < snapshot.size(); offset++) {
                String stream = snapshot.get((start + offset) % snapshot.size());
                PendingSummary pending = pendingSummary(stream);
                pendingCount += pending.count();
                oldestIdle = Math.max(oldestIdle, pending.oldestIdleMs());
                consumedAny |= claimOnePage(stream);
                consumedAny |= readNew(stream, false);
            }
            metrics.pending(pendingCount, oldestIdle);
            if (!consumedAny) {
                readNew(snapshot.get(start), true);
            }
        } catch (DataAccessException exception) {
            metrics.failures().increment();
            log.warn("Seckill Stream poll deferred because a dependency is unavailable");
        } catch (RuntimeException exception) {
            metrics.failures().increment();
            log.error("Seckill Stream poll failed with category {}", exception.getClass().getSimpleName());
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

    private void process(
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
        Timer.Sample sample = Timer.start();
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
                            reservationGateway.compensate(
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
            handleFailure(
                    stream,
                    entryId,
                    event,
                    SeckillProcessingService.FailureKind.SAFE_INVENTORY,
                    exception.getMessage()
            );
        } catch (SeckillProcessingService.ManualFactFailure exception) {
            handleFailure(
                    stream,
                    entryId,
                    event,
                    SeckillProcessingService.FailureKind.MANUAL,
                    exception.getMessage()
            );
        } catch (DataAccessException exception) {
            recordTransientBestEffort(stream, entryId, event, "DEPENDENCY_UNAVAILABLE");
            metrics.failures().increment();
        } catch (RuntimeException exception) {
            recordTransientBestEffort(stream, entryId, event, "UNEXPECTED_PROCESSING_FAILURE");
            metrics.failures().increment();
        } finally {
            sample.stop(metrics.conversionLatency());
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
                reservationGateway.compensate(event, compensationId, reasonCode);
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
        Object nativeConnection = connection.getNativeConnection();
        if (!(nativeConnection instanceof RedisStreamAsyncCommands<?, ?> async)) {
            throw new IllegalStateException("Redis driver does not expose Stream commands");
        }
        RedisStreamAsyncCommands<byte[], byte[]> commands =
                (RedisStreamAsyncCommands<byte[], byte[]>) async;
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
