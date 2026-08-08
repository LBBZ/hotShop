package com.real.task.seckill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class SeckillOrderMetrics {
    private final MeterRegistry registry;
    private final Counter consumed;
    private final Counter processed;
    private final Counter duplicate;
    private final Counter retried;
    private final Counter claimed;
    private final Counter quarantined;
    private final Counter manualReview;
    private final Counter compensated;
    private final Counter failures;
    private final Counter reconciliationFindings;
    private final Timer conversionLatency;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingIdleMs = new AtomicLong();
    private final AtomicLong streamLag = new AtomicLong();

    public SeckillOrderMetrics(MeterRegistry registry) {
        this.registry = registry;
        consumed = registry.counter("hotshop.seckill.order.consumed");
        processed = registry.counter("hotshop.seckill.order.processed");
        duplicate = registry.counter("hotshop.seckill.order.duplicate");
        retried = registry.counter("hotshop.seckill.order.retried");
        claimed = registry.counter("hotshop.seckill.order.claimed");
        quarantined = registry.counter("hotshop.seckill.order.quarantined");
        manualReview = registry.counter("hotshop.seckill.order.manual_review");
        compensated = registry.counter("hotshop.seckill.order.compensated");
        failures = registry.counter("hotshop.seckill.order.processing_failures");
        reconciliationFindings = registry.counter("hotshop.seckill.reconciliation.findings");
        conversionLatency = registry.timer("hotshop.seckill.order.conversion_latency");
        registry.gauge("hotshop.seckill.order.pending", pending);
        registry.gauge("hotshop.seckill.order.pending_oldest_idle_ms", oldestPendingIdleMs);
        registry.gauge("hotshop.seckill.stream.lag", streamLag);
    }

    public Counter consumed() {
        return consumed;
    }

    public Counter processed() {
        return processed;
    }

    public Counter duplicate() {
        return duplicate;
    }

    public Counter retried() {
        return retried;
    }

    public Counter claimed() {
        return claimed;
    }

    public Counter quarantined() {
        return quarantined;
    }

    public Counter manualReview() {
        return manualReview;
    }

    public Counter compensated() {
        return compensated;
    }

    public Counter failures() {
        return failures;
    }

    public Counter reconciliationFindings() {
        return reconciliationFindings;
    }

    public Timer conversionLatency() {
        return conversionLatency;
    }

    public void pending(long count, long oldestIdleMs) {
        pending.set(Math.max(0, count));
        oldestPendingIdleMs.set(Math.max(0, oldestIdleMs));
    }

    public void streamLag(long lag) {
        streamLag.set(Math.max(0, lag));
    }

    public void inventory(String operation, String outcome) {
        registry.counter("hotshop.inventory.operations", "operation", operation,
                "outcome", outcome).increment();
    }
}
