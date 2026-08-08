package com.real.task.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TaskObservabilityMetricsTest {
    @Test
    void recordsOnlyBoundedOperationAndOutcomeDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskObservabilityMetrics metrics = new TaskObservabilityMetrics(registry);

        metrics.outbox("confirmed", Duration.ofMillis(12));
        metrics.rabbit("consume", "ack");
        metrics.inventory("compensate", "success");
        metrics.paymentDelivery("retry");

        assertThat(registry.get("hotshop.outbox.publish")
                .tag("outcome", "confirmed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("hotshop.outbox.publish.duration")
                .tag("outcome", "confirmed").timer().count()).isEqualTo(1);
        assertThat(registry.get("hotshop.rabbitmq.deliveries")
                .tags("operation", "consume", "outcome", "ack").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("hotshop.inventory.operations")
                .tags("operation", "compensate", "outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("hotshop.payment.callback.delivery")
                .tag("outcome", "retry").counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey())
                                .isIn("outcome", "operation")));
    }
}
