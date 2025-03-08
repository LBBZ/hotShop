package com.real.admin.controller;

import com.github.pagehelper.PageInfo;
import com.real.common.enums.OrderStatus;
import com.real.domain.entity.baseEntity.Order;
import com.real.domain.service.baseService.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/orders")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminOrderController {
    private final OrderService orderService;
    @Autowired
    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
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
    public ResponseEntity<PageInfo<Order>> getOrders(
            @RequestParam(defaultValue = "1") int orderPage,
            @RequestParam(defaultValue = "10") int orderPageSize,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String statusStr,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime
    ) {
        OrderStatus status = OrderStatus.strTransitionToEnums(statusStr);
        PageInfo<Order> orders = orderService.getOrdersByConditions(orderPage, orderPageSize, userId, status, startTime, endTime);
        return ResponseEntity.ok(orders);
    }
}