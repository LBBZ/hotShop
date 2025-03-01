package com.real.portal.controller;


import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.service.baseService.OrderService;
import com.real.domain.service.orderStateService.OrderStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderStateService orderStateService;

    @Autowired
    public OrderController(OrderService orderService, OrderStateService orderStateService) {
        this.orderService = orderService;
        this.orderStateService = orderStateService;
    }

    @PostMapping("/add")
    public ResponseEntity<String> createOrder(@RequestBody Order order) {
        String orderId = orderStateService.createOrder(order);
        return ResponseEntity.ok("订单创建成功，订单号: " + orderId);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Order>> getOrdersByUserId(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int orderPage,
            @RequestParam(defaultValue = "10") int orderPageSize,
            @RequestParam(defaultValue = "1") int itemPage,
            @RequestParam(defaultValue = "3") int itemPageSize
    ) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId, orderPage, orderPageSize, itemPage, itemPageSize));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Order>> getOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String statusStr,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime
    ) {
        OrderStatus status = OrderStatus.strTransitionToEnums(statusStr);
        List<Order> orders = orderService.getOrdersByConditions(userId, status, startTime, endTime);
        return ResponseEntity.ok(orders);
    }
}