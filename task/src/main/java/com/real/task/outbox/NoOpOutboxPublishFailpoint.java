package com.real.task.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(OutboxPublishFailpoint.class)
final class NoOpOutboxPublishFailpoint implements OutboxPublishFailpoint { }
