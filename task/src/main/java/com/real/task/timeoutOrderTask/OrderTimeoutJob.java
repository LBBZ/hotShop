package com.real.task.timeoutOrderTask;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
@EnableScheduling
public class OrderTimeoutJob {
    // timeoutThreshold 分钟为单位
    @Value("${timeout.scheduled}")
    private long timeoutThreshold;
    private final OrderService orderService;
    private final OrderStateService orderStateService;
    private final Clock clock;

    @Autowired
    public OrderTimeoutJob(OrderService orderService, OrderStateService orderStateService) {
        this(orderService, orderStateService, Clock.systemUTC());
    }

    OrderTimeoutJob(OrderService orderService, OrderStateService orderStateService, Clock clock) {
        this.orderService = orderService;
        this.orderStateService = orderStateService;
        this.clock = clock;
    }

    // 定时处理订单
    @Scheduled(fixedRate = 60_000)
    public void checkTimeoutOrders() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(timeoutThreshold);
        List<Order> timeoutOrders = orderService.getOrdersByConditions(null, OrderStatus.PENDING, null, threshold);

        timeoutOrders.parallelStream().forEach(order ->
                orderStateService.cancelOrder(order.getOrderId())
        );
    }

}
