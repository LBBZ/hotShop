package com.real.task.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("hotshop.outbox.publisher")
public record OutboxPublisherProperties(boolean enabled, int batchSize, Duration pollDelay, Duration lease,
        Duration confirmTimeout, Duration initialBackoff, Duration maxBackoff,
        double multiplier, int maxAttempts) {
    public OutboxPublisherProperties {
        if (batchSize <= 0 || maxAttempts <= 0 || !Double.isFinite(multiplier) || multiplier <= 1.0
                || !positive(pollDelay) || !positive(lease) || !positive(confirmTimeout)
                || !positive(initialBackoff) || !positive(maxBackoff)
                || maxBackoff.compareTo(initialBackoff) < 0
                || confirmTimeout.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("Invalid outbox publisher configuration");
        }
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
