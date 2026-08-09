package com.real.portal.sse;

import com.real.common.api.dto.TransactionTimelineEventResponse;
import com.real.common.api.ApiException;
import com.real.portal.timeline.TransactionTimelineService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

@Service
public class TransactionEventStreamService {
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long HEARTBEAT_INTERVAL_MILLIS = Duration.ofSeconds(15).toMillis();
    private static final long POLL_INTERVAL_MILLIS = Duration.ofSeconds(2).toMillis();
    private static final int MAX_CONNECTIONS = 200;

    private final TransactionTimelineService timeline;
    private final long heartbeatIntervalMillis;
    private final long pollIntervalMillis;
    private final int maxConnections;
    private final LongFunction<SseEmitter> emitterFactory;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ConcurrentHashMap<SseEmitter, Runnable> connectionClosers =
            new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor executor;

    @Autowired
    public TransactionEventStreamService(TransactionTimelineService timeline) {
        this(
                timeline,
                createExecutor(),
                SseEmitter::new,
                HEARTBEAT_INTERVAL_MILLIS,
                POLL_INTERVAL_MILLIS,
                MAX_CONNECTIONS
        );
    }

    TransactionEventStreamService(
            TransactionTimelineService timeline,
            ScheduledThreadPoolExecutor executor,
            LongFunction<SseEmitter> emitterFactory,
            long heartbeatIntervalMillis,
            long pollIntervalMillis,
            int maxConnections
    ) {
        this.timeline = timeline;
        this.executor = executor;
        this.emitterFactory = emitterFactory;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.pollIntervalMillis = pollIntervalMillis;
        this.maxConnections = maxConnections;
    }

    public SseEmitter order(String orderId, long userId, long lastEventId) {
        timeline.requireOwnedOrder(orderId, userId);
        return open(lastEventId, cursor -> timeline.durableOrderEvents(orderId, userId, cursor));
    }

    public SseEmitter reservation(
            long activityId,
            String reservationNo,
            long userId,
            long lastEventId
    ) {
        timeline.requireOwnedReservation(activityId, reservationNo, userId);
        return open(lastEventId,
                cursor -> timeline.durableReservationEvents(reservationNo, userId, cursor));
    }

    private SseEmitter open(long lastEventId, LongFunction<List<TransactionTimelineEventResponse>> loader) {
        if (activeConnections.incrementAndGet() > maxConnections) {
            activeConnections.decrementAndGet();
            throw ApiException.serviceUnavailable(
                    "SSE_CAPACITY_EXHAUSTED", "Transaction event stream capacity is exhausted");
        }
        final SseEmitter emitter;
        try {
            emitter = Objects.requireNonNull(
                    emitterFactory.apply(STREAM_TIMEOUT_MILLIS),
                    "emitterFactory returned null"
            );
        } catch (RuntimeException exception) {
            activeConnections.decrementAndGet();
            throw exception;
        }
        AtomicLong cursor = new AtomicLong(lastEventId);
        AtomicLong lastWrite = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean released = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

        Runnable close = () -> {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> future = task.get();
            if (future != null) {
                future.cancel(false);
            }
            connectionClosers.remove(emitter);
            activeConnections.decrementAndGet();
        };
        try {
            connectionClosers.put(emitter, close);
            emitter.onCompletion(close);
            emitter.onTimeout(close);
            emitter.onError(error -> close.run());

            ScheduledFuture<?> scheduled = executor.scheduleWithFixedDelay(() -> {
                try {
                    List<TransactionTimelineEventResponse> events = loader.apply(cursor.get());
                    for (TransactionTimelineEventResponse event : events) {
                        emitter.send(SseEmitter.event()
                                .id(Long.toString(event.eventId()))
                                .name(event.eventType())
                                .data(event));
                        cursor.updateAndGet(current -> Math.max(current, event.eventId()));
                        lastWrite.set(System.currentTimeMillis());
                    }
                    if (events.isEmpty()
                            && System.currentTimeMillis() - lastWrite.get() >= heartbeatIntervalMillis) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        lastWrite.set(System.currentTimeMillis());
                    }
                } catch (IOException exception) {
                    close.run();
                    emitter.complete();
                } catch (RuntimeException exception) {
                    close.run();
                    emitter.completeWithError(exception);
                }
            }, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
            task.set(scheduled);
            if (released.get()) {
                scheduled.cancel(false);
            }
        } catch (RuntimeException exception) {
            close.run();
            throw exception;
        }
        return emitter;
    }

    int activeConnections() {
        return activeConnections.get();
    }

    int registeredConnections() {
        return connectionClosers.size();
    }

    @PreDestroy
    void shutdown() {
        List.copyOf(connectionClosers.values()).forEach(Runnable::run);
        executor.shutdownNow();
    }

    static ScheduledThreadPoolExecutor createExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(8, runnable -> {
            Thread thread = new Thread(runnable, "hotshop-user-transaction-sse");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
