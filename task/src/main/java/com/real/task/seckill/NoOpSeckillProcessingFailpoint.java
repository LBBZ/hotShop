package com.real.task.seckill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(SeckillProcessingFailpoint.class)
public final class NoOpSeckillProcessingFailpoint implements SeckillProcessingFailpoint {
}
