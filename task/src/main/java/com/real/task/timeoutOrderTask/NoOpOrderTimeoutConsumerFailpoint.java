package com.real.task.timeoutOrderTask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(OrderTimeoutConsumerFailpoint.class)
final class NoOpOrderTimeoutConsumerFailpoint implements OrderTimeoutConsumerFailpoint { }
