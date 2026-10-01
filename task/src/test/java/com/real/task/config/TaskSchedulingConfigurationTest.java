package com.real.task.config;

import com.real.task.outbox.OutboxPublisher;
import com.real.task.seckill.ReservationStreamConsumer;
import com.real.task.seckill.SeckillReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSchedulingConfigurationTest {
    @Test
    void closingContextCancelsDelayedAndPeriodicTasksAndTerminatesAllSchedulers() {
        var context = new AnnotationConfigApplicationContext(TaskSchedulingConfiguration.class);
        var schedulers = context.getBeansOfType(ThreadPoolTaskScheduler.class).values();
        var futures = new ArrayList<ScheduledFuture<?>>();
        try {
            for (var scheduler : schedulers) {
                futures.add(scheduler.schedule(() -> { }, Instant.now().plusSeconds(3600)));
                futures.add(scheduler.scheduleAtFixedRate(() -> { },
                        Instant.now().plusSeconds(3600), Duration.ofHours(1)));
            }
        } finally {
            context.close();
        }

        assertThat(futures).allSatisfy(future -> assertThat(future.isCancelled()).isTrue());
        assertThat(schedulers).hasSize(4).allSatisfy(scheduler ->
                assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue());
    }

    @Test
    void blockedOutboxDoesNotStarveConsumptionOrReconciliation() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TaskSchedulingConfiguration.class)) {
            String outboxName = OutboxPublisher.class.getMethod("poll")
                    .getAnnotation(Scheduled.class).scheduler();
            String consumerName = ReservationStreamConsumer.class.getMethod("poll")
                    .getAnnotation(Scheduled.class).scheduler();
            String reconciliationName = SeckillReconciliationService.class.getMethod("scheduledReconciliation")
                    .getAnnotation(Scheduled.class).scheduler();
            var outbox = context.getBean(outboxName, ThreadPoolTaskScheduler.class);
            var consumer = context.getBean(consumerName, ThreadPoolTaskScheduler.class);
            var reconciliation = context.getBean(reconciliationName, ThreadPoolTaskScheduler.class);
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch progressed = new CountDownLatch(2);
            outbox.execute(() -> {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();
                consumer.execute(progressed::countDown);
                reconciliation.execute(progressed::countDown);
                assertThat(progressed.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }
}
