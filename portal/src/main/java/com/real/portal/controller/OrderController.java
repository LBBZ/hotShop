package com.real.portal.controller;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.baseEntity.Order;
import com.real.security.entity.CustomUserDetails;
import com.real.domain.service.baseService.OrderService;
import com.real.domain.service.orderStateService.OrderStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/orders")
@PreAuthorize("hasRole('ROLE_USER')")
public class OrderController {

    private final OrderService orderService;
    private final OrderStateService orderStateService;

    @Autowired
    public OrderController(OrderService orderService, OrderStateService orderStateService) {
        this.orderService = orderService;
        this.orderStateService = orderStateService;
    }

    @PostMapping("/add")
    public ResponseEntity<String> createOrderByUserToken(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                              @RequestBody Order order) {
        order.setUserId(customUserDetails.getUserId());
        String orderId = orderStateService.createOrder(order);
        return ResponseEntity.ok("订单创建成功，订单号: " + orderId);
    }

    @GetMapping("/page")
    public ResponseEntity<List<Order>> getOrdersByUserToken(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(defaultValue = "1") int orderPage,
            @RequestParam(defaultValue = "10") int orderPageSize,
            @RequestParam(defaultValue = "1") int itemPage,
            @RequestParam(defaultValue = "3") int itemPageSize
    ) {
        return ResponseEntity.ok(orderService.getOrdersByUserId(customUserDetails.getUserId(), orderPage, orderPageSize, itemPage, itemPageSize));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Order>> getOrdersByUserToken(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) String statusStr,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime
    ) {
        OrderStatus status = OrderStatus.strTransitionToEnums(statusStr);
        List<Order> orders = orderService.getOrdersByConditions(customUserDetails.getUserId(), status, startTime, endTime);
        return ResponseEntity.ok(orders);
    }
}