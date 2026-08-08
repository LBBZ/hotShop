package com.real.task.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TaskObservabilityMetrics {
    private final MeterRegistry meters;

    public TaskObservabilityMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    public void outbox(String outcome, Duration elapsed) {
        meters.counter("hotshop.outbox.publish", "outcome", outcome).increment();
        Timer.builder("hotshop.outbox.publish.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meters)
                .record(elapsed);
    }

    public void rabbit(String operation, String outcome) {
        meters.counter("hotshop.rabbitmq.deliveries", "operation", operation,
                "outcome", outcome).increment();
    }

    public void inventory(String operation, String outcome) {
        meters.counter("hotshop.inventory.operations", "operation", operation,
                "outcome", outcome).increment();
    }

    public void paymentDelivery(String outcome) {
        meters.counter("hotshop.payment.callback.delivery", "outcome", outcome).increment();
    }
}
