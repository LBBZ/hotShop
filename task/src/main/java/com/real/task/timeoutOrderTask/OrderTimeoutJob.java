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

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class OrderTimeoutJob {
    // timeoutThreshold 分钟为单位
    @Value("${timeout.orderCancel}")
    private long timeoutThreshold;
    private final OrderService orderService;
    private final OrderStateService orderStateService;
    @Autowired
    public OrderTimeoutJob(OrderService orderService, OrderStateService orderStateService) {
        this.orderService = orderService;
        this.orderStateService = orderStateService;
    }

    // 每10分钟执行一次（注意时区）
    @Scheduled(cron = "0 */10 * * * ?", zone = "Asia/Shanghai")
    public void checkTimeoutOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutThreshold);
        List<Order> timeoutOrders = orderService.getOrdersByConditions(null, OrderStatus.PENDING, null, threshold);

        timeoutOrders.parallelStream().forEach(order ->
                orderStateService.cancelOrder(order.getOrderId())
        );
    }

}
