package com.real.portal.job;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.service.OrderService;
import com.real.domain.service.OrderStateService;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final OrderStateService orderStateService;
    private final OrderService orderService;

    @Autowired
    public OrderTimeoutJob(OrderStateService orderStateService, OrderService orderService) {
        this.orderStateService = orderStateService;
        this.orderService = orderService;
    }

    // 定时清理超时未支付订单
    @Scheduled(initialDelay = 10_000, fixedRate = 60_000)
    public void cancelTimeoutOrders() {
        List<String> timeoutOrderIds = getTimeoutOrders(30); // 30分钟未支付
        timeoutOrderIds.forEach(orderId -> {
            Order order = orderService.getOrderById(orderId);
            orderStateService.changeOrderStatus(order, OrderStatus.CANCELED);
        });
    }

    /**
     * 获取超时未支付订单
     * @param timeoutThreshold 分钟为单位
     * @return 超时未支付订单订单号
     */
    private List<String> getTimeoutOrders(long timeoutThreshold) {
        long finalTimeoutThreshold = timeoutThreshold * 60;
        LocalDateTime now = LocalDateTime.now();
        List<Order> pendingOrders = orderService.getOrdersByStatus(OrderStatus.PENDING);
        List<String> timeoutOrderIds;
        timeoutOrderIds = pendingOrders.stream()
                .filter(pendingOrder -> {
                    LocalDateTime creatAt = pendingOrder.getCreatedAt();
                    long duringSeconds = Duration.between(creatAt, now).getSeconds();
                    return duringSeconds > finalTimeoutThreshold;
                })
                .map(Order::getId)
                .collect(Collectors.toList());

        return timeoutOrderIds;
    }

}
