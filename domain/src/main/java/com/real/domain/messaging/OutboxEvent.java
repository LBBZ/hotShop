package com.real.domain.messaging;

import java.time.LocalDateTime;

public record OutboxEvent(long outboxId, String eventId, String aggregateType,
                          String aggregateId, String eventType, String payload,
                          String status, int publishAttempts, int consecutiveAttempts,
                          String leaseToken, LocalDateTime leaseExpiresAt, long version,
                          LocalDateTime createdAt) {
}
