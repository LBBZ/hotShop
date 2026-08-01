package com.real.task.timeoutOrderTask;

import com.real.domain.infra.RedisService;
import com.real.domain.service.advance.OrderStateService;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = "order.delay.queue")
public class OrderTimeoutConsumer {
    private final RedisService redisService;
    private final OrderStateService orderStateService;
    @Autowired
    public OrderTimeoutConsumer(RedisService redisService, OrderStateService orderStateService) {
        this.redisService = redisService;
        this.orderStateService = orderStateService;
    }

    @RabbitHandler
    public void handleOrderTimeout(String orderId) {
        // 获取分布式锁（防止重复消费）
        Boolean lockAcquired = redisService.setWithTTL(
                "lock:order:" + orderId, true,
                15,10
        );


        if (Boolean.TRUE.equals(lockAcquired)) {
            try {
                orderStateService.cancelLegacyPendingOrder(orderId);
            } finally {
                redisService.delete("lock:order:" + orderId, 15);
            }
        }
    }
}
