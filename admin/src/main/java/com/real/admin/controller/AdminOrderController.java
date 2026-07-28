package com.real.admin.controller;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.OrderResponse;
import com.real.common.enums.OrderStatus;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Order;
import com.real.domain.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Validated
@Tag(name = "Admin orders", description = "Administrator Order queries")
@RequestMapping("/admin/api/v1/orders")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrderController {
    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Get an Order")
    @GetMapping("/{orderId}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_ORDER_READ')")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$")
            String orderId
    ) {
        Order order = orderService.getOrderById(orderId);
        if (order == null) {
            throw ApiException.notFound("Order");
        }
        return ResponseEntity.ok(ApiDtoMapper.toOrderResponse(order));
    }

    @Operation(
            summary = "List Orders",
            description = "Stable keyset pagination ordered by createdAt descending, then orderId descending"
    )
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ADMIN_ORDER_READ')")
    public ResponseEntity<CursorPageResponse<OrderResponse>> getOrders(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) Long userId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo
    ) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw ApiException.badRequest(
                    "TIME_RANGE_INVALID",
                    "createdFrom must be before or equal to createdTo"
            );
        }
        CursorSlice<Order> slice = orderService.getAdminOrdersByCursor(
                userId,
                limit,
                cursor,
                status,
                ApiDtoMapper.toUtcLocalDateTime(createdFrom),
                ApiDtoMapper.toUtcLocalDateTime(createdTo)
        );
        return ResponseEntity.ok(new CursorPageResponse<>(
                slice.items().stream().map(ApiDtoMapper::toOrderResponse).toList(),
                slice.nextCursor(),
                slice.hasMore()
        ));
    }
}
