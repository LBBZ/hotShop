package com.real.task.outbox;

import com.real.domain.messaging.OutboxEvent;

public interface OutboxPublishFailpoint {
    default void afterClaim(OutboxEvent event) { }
    default void afterBrokerConfirm(OutboxEvent event) { }
}
