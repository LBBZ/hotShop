package com.real.admin.controller;

import com.github.pagehelper.PageInfo;
import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@Tag(name = "管理端-订单管理", description = "全平台订单管理（需管理员权限）")
@RequestMapping("/admin/orders")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminOrderController {
    private final OrderService orderService;
    @Autowired
    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(
            summary = "查看订单详情",
            description = "根据订单ID查看完整订单信息",
            security = @SecurityRequirement(name = "JWT"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "订单详情"),
                    @ApiResponse(responseCode = "404", description = "订单不存在")
            }
    )
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @Operation(
            summary = "用户订单查询",
            description = "查看指定用户的所有订单（分页）",
            security = @SecurityRequirement(name = "JWT"),
            parameters = {
                    @Parameter(name = "userId", description = "用户ID", example = "123", required = true),
                    @Parameter(name = "orderPage", description = "订单页码", example = "1"),
                    @Parameter(name = "orderPageSize", description = "每页订单数", example = "10"),
                    @Parameter(name = "itemPage", description = "订单项页码", example = "1"),
                    @Parameter(name = "itemPageSize", description = "每页订单项数", example = "3")
            }
    )
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

    @Operation(
        summary = "高级订单搜索",
        description = "多条件组合查询平台订单",
        security = @SecurityRequirement(name = "JWT"),
        parameters = {
            @Parameter(name = "orderPage", description = "页码", example = "1"),
            @Parameter(name = "orderPageSize", description = "每页数量", example = "10"),
            @Parameter(name = "userId", description = "用户ID过滤", example = "123"),
            @Parameter(name = "statusStr", description = "订单状态过滤",
               schema = @Schema(implementation = OrderStatus.class, example = "PAID")),
            @Parameter(name = "startTime", description = "下单时间起",
                example = "2023-01-01 00:00:00"),
            @Parameter(name = "endTime", description = "下单时间止",
                example = "2023-12-31 23:59:59")
        },
        responses = @ApiResponse(
                content = @Content(schema = @Schema(implementation = PageInfo.class, example = """
            {
              "pageNum": 1,
              "pageSize": 10,
              "total": 50,
              "list": [
                {
                  "orderId": "ORDER_20231001123456",
                  "totalAmount": 13998.00,
                  "status": "PAID",
                  "createdAt": "2023-10-01T12:00:00"
                }
              ]
            }"""))
        )
    )
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