package com.real.portal.controller;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.security.entity.CustomUserDetails;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@Tag(name = "订单接口", description = "用户订单管理（需要用户权限）")
@RequestMapping("/portal/orders")
@PreAuthorize("hasRole('ROLE_USER')")
public class OrderController {
    private final OrderService orderService;
    private final OrderStateService orderStateService;
    @Autowired
    public OrderController(OrderService orderService, OrderStateService orderStateService) {
        this.orderService = orderService;
        this.orderStateService = orderStateService;
    }

    @Operation(
        summary = "创建订单",
        description = "根据购物车生成新订单",
        security = @SecurityRequirement(name = "JWT"),
        responses = {
            @ApiResponse(responseCode = "200", description = "订单创建成功"),
            @ApiResponse(responseCode = "401", description = "未授权访问"),
            @ApiResponse(responseCode = "400", description = "库存不足或其他验证错误")
        }
    )
    @PostMapping("/add")
    public ResponseEntity<String> createOrderByUserToken(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestBody Order order) {
        order.setUserId(customUserDetails.getUserId());
        String orderId = orderStateService.createOrder(order);
        return ResponseEntity.ok("订单创建成功，订单号: " + orderId);
    }

    @Operation(
        summary = "分页查询订单",
        description = "获取用户的分页订单列表（包含订单项分页）",
        security = @SecurityRequirement(name = "JWT"),
        parameters = {
            @Parameter(name = "orderPage", description = "订单页码", example = "1"),
            @Parameter(name = "orderPageSize", description = "每页订单数量", example = "10"),
            @Parameter(name = "itemPage", description = "订单项页码", example = "1"),
            @Parameter(name = "itemPageSize", description = "每页订单项数量", example = "3")
        }
    )
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

    @Operation(
        summary = "条件搜索订单",
        description = "根据状态和时间范围查询订单",
        security = @SecurityRequirement(name = "JWT"),
        parameters = {
            @Parameter(name = "statusStr", description = "订单状态", example = "PAID",
                schema = @Schema(implementation = OrderStatus.class)),
            @Parameter(name = "startTime", description = "开始时间", example = "2023-01-01 00:00:00"),
            @Parameter(name = "endTime", description = "结束时间", example = "2023-12-31 23:59:59")
        }
    )
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