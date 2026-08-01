package com.real.domain;

import com.real.infrastructure.RabbitMQ.RabbitMQConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReliableRabbitTopologyTest {
    @Test
    void fixedFifteenMinuteTtlIsExactlyNineHundredThousandMilliseconds() {
        var queue = new RabbitMQConfig(Duration.ofMinutes(15)).timeoutDelayQueue();

        assertThat(queue.getArguments())
                .containsEntry("x-message-ttl", 900_000)
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.TIMEOUT_READY_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.TIMEOUT_ROUTING_KEY);
    }

    @Test
    void queueTtlRejectsNonPositiveAndOverflowingDurations() {
        assertThatThrownBy(() -> new RabbitMQConfig(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RabbitMQConfig(Duration.ofMillis((long) Integer.MAX_VALUE + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
