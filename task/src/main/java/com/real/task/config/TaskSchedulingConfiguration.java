package com.real.task.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Blocking dependencies in one pipeline must not starve another pipeline. */
@Configuration(proxyBeanMethods = false)
public class TaskSchedulingConfiguration {
    @Bean
    public ThreadPoolTaskScheduler reservationScheduler() {
        return scheduler("reservation-");
    }

    @Bean
    public ThreadPoolTaskScheduler outboxScheduler() {
        return scheduler("outbox-");
    }

    @Bean
    public ThreadPoolTaskScheduler reconciliationScheduler() {
        return scheduler("reconciliation-");
    }

    @Bean
    public ThreadPoolTaskScheduler maintenanceScheduler() {
        return scheduler("maintenance-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Preserve sequential execution within each pipeline (consumer cursors are local state).
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
