package com.real.portal.sse;

import com.real.common.api.ApiException;
import com.real.portal.timeline.TransactionTimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionEventStreamServiceTest {
    private final TransactionTimelineService timeline = mock(TransactionTimelineService.class);
    private ScheduledThreadPoolExecutor scheduler;
    private ControllableSseEmitter emitter;
    private TransactionEventStreamService service;

    @BeforeEach
    void createService() {
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        service = new TransactionEventStreamService(
                timeline,
                scheduler,
                timeout -> {
                    emitter = new ControllableSseEmitter(timeout);
                    return emitter;
                },
                Duration.ofMinutes(1).toMillis(),
                Duration.ofMinutes(1).toMillis(),
                200
        );
    }

    @AfterEach
    void stopScheduler() {
        service.shutdown();
    }

    @Test
    void passesLastEventIdAndReleasesFailedPollingTaskExactlyOnce() {
        AtomicLong observedCursor = new AtomicLong(-1);
        when(timeline.durableOrderEvents(eq("order-1"), eq(7L), anyLong()))
                .thenAnswer(invocation -> {
                    observedCursor.set(invocation.getArgument(2));
                    throw new IllegalStateException("connection lost");
                });

        service.order("order-1", 7L, 41L);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(observedCursor).hasValue(41L);
            assertThat(service.activeConnections()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        });
        verify(timeline, times(1)).durableOrderEvents("order-1", 7L, 41L);
    }

    @Test
    void completionCancelsPollingTaskAndReturnsCapacityExactlyOnce() {
        openIdleOrder("completed-order", 9L);

        emitter.signalCompletion();
        emitter.signalCompletion();

        assertReleased();
        verify(timeline, times(1)).durableOrderEvents("completed-order", 9L, 0L);
    }

    @Test
    void timeoutCancelsPollingTaskAndReturnsCapacityExactlyOnce() {
        openIdleOrder("timed-out-order", 10L);

        emitter.signalTimeout();
        emitter.signalTimeout();

        assertReleased();
        verify(timeline, times(1)).durableOrderEvents("timed-out-order", 10L, 0L);
    }

    @Test
    void errorCancelsPollingTaskAndReturnsCapacityExactlyOnce() {
        openIdleOrder("failed-order", 11L);

        emitter.signalError(new IllegalStateException("client disconnected"));
        emitter.signalError(new IllegalStateException("duplicate notification"));

        assertReleased();
        verify(timeline, times(1)).durableOrderEvents("failed-order", 11L, 0L);
    }

    @Test
    void rejectsUnownedResourceBeforeAllocatingCapacityOrStartingPolling() {
        doThrow(ApiException.notFound("Order"))
                .when(timeline).requireOwnedOrder("another-users-order", 12L);

        assertThatThrownBy(() -> service.order("another-users-order", 12L, 0L))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getStatus().value()).isEqualTo(404));

        assertThat(service.activeConnections()).isZero();
        assertThat(scheduler.getQueue()).isEmpty();
        verify(timeline, never()).durableOrderEvents(eq("another-users-order"), eq(12L), anyLong());
    }

    @Test
    void enforcesCapacityAndShutdownReleasesEveryConnection() {
        when(timeline.durableOrderEvents(eq("order-2"), eq(8L), anyLong()))
                .thenReturn(List.of());

        for (int connection = 0; connection < 200; connection++) {
            service.order("order-2", 8L, 0L);
        }

        assertThat(service.activeConnections()).isEqualTo(200);
        assertThatThrownBy(() -> service.order("order-2", 8L, 0L))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("SSE_CAPACITY_EXHAUSTED");
                    assertThat(exception.getStatus().value()).isEqualTo(503);
                });

        service.shutdown();
        assertThat(service.activeConnections()).isZero();
        assertThat(scheduler.getQueue()).isEmpty();
    }

    @Test
    void productionExecutorRemovesCompletedConnectionTaskImmediately() {
        service.shutdown();
        scheduler = TransactionEventStreamService.createExecutor();
        service = new TransactionEventStreamService(
                timeline,
                scheduler,
                timeout -> {
                    emitter = new ControllableSseEmitter(timeout);
                    return emitter;
                },
                Duration.ofMinutes(1).toMillis(),
                Duration.ofMinutes(1).toMillis(),
                200
        );

        assertThat(scheduler.getRemoveOnCancelPolicy()).isTrue();
        openIdleOrder("production-executor-order", 20L);
        emitter.signalCompletion();

        assertReleased();
        assertThat(service.registeredConnections()).isZero();
    }

    @Test
    void emitterFactoryFailureRollsBackCapacityWithoutRegisteringResources() {
        service.shutdown();
        scheduler = TransactionEventStreamService.createExecutor();
        IllegalStateException factoryFailure = new IllegalStateException("emitter unavailable");
        service = new TransactionEventStreamService(
                timeline,
                scheduler,
                timeout -> {
                    throw factoryFailure;
                },
                Duration.ofMinutes(1).toMillis(),
                Duration.ofMinutes(1).toMillis(),
                200
        );

        assertThatThrownBy(() -> service.order("factory-failure-order", 21L, 0L))
                .isSameAs(factoryFailure);
        assertThat(service.activeConnections()).isZero();
        assertThat(service.registeredConnections()).isZero();
        assertThat(scheduler.getQueue()).isEmpty();
    }

    @Test
    void schedulerRejectionCleansRegisteredEmitterAndRollsBackCapacity() {
        scheduler.shutdownNow();

        assertThatThrownBy(() -> service.order("rejected-order", 22L, 0L))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(service.activeConnections()).isZero();
        assertThat(service.registeredConnections()).isZero();
        assertThat(scheduler.getQueue()).isEmpty();

        emitter.signalCompletion();
        assertThat(service.activeConnections()).isZero();
    }

    private void openIdleOrder(String orderId, long userId) {
        when(timeline.durableOrderEvents(orderId, userId, 0L)).thenReturn(List.of());

        service.order(orderId, userId, 0L);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(timeline, times(1)).durableOrderEvents(orderId, userId, 0L));
        assertThat(service.activeConnections()).isOne();
    }

    private void assertReleased() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(service.activeConnections()).isZero();
            assertThat(service.registeredConnections()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        });
    }

    private static final class ControllableSseEmitter extends SseEmitter {
        private Runnable completion;
        private Runnable timeout;
        private Consumer<Throwable> error;

        private ControllableSseEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public void onCompletion(Runnable callback) {
            super.onCompletion(callback);
            completion = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            super.onTimeout(callback);
            timeout = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            super.onError(callback);
            error = callback;
        }

        private void signalCompletion() {
            completion.run();
        }

        private void signalTimeout() {
            timeout.run();
        }

        private void signalError(Throwable throwable) {
            error.accept(throwable);
        }
    }
}
