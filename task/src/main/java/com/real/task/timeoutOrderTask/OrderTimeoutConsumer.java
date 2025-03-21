package com.real.task.timeoutOrderTask;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.infra.RedisService;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = "order.delay.queue")
public class OrderTimeoutConsumer {
    private final RedisService redisService;
    private final OrderService orderService;
    private final OrderStateService orderStateService;
    @Autowired
    public OrderTimeoutConsumer(RedisService redisService, OrderService orderService, OrderStateService orderStateService) {
        this.redisService = redisService;
        this.orderService = orderService;
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
                Order order = orderService.getOrderById(orderId);
                if (order.getStatus() == OrderStatus.PENDING) {
                    orderStateService.cancelOrder(orderId);
                }
            } finally {
                redisService.delete("lock:order:" + orderId, 15);
            }
        }
    }
}
