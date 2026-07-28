package com.real.portal.controller;

import com.real.common.api.CursorSlice;
import com.real.common.api.ApiException;
import com.real.common.api.dto.CreateOrderRequest;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.OrderCreatedResponse;
import com.real.common.api.dto.OrderResponse;
import com.real.common.enums.OrderStatus;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Order;
import com.real.domain.infra.RabbitMQService;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Validated
@Tag(name = "User orders", description = "Orders owned by the authenticated User")
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {
    @Value("${timeout.orderCancel}")
    private long timeoutThreshold;

    private final OrderService orderService;
    private final OrderStateService orderStateService;
    private final RabbitMQService rabbitMQService;

    public OrderController(
            OrderService orderService,
            OrderStateService orderStateService,
            RabbitMQService rabbitMQService
    ) {
        this.orderService = orderService;
        this.orderStateService = orderStateService;
        this.rabbitMQService = rabbitMQService;
    }

    @Operation(
            summary = "Create an Order",
            description = "Creates an Order synchronously. Idempotency-Key is not supported until persistent replay is implemented."
    )
    @PostMapping
    public ResponseEntity<OrderCreatedResponse> createOrder(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody @Valid CreateOrderRequest request
    ) {
        Order order = ApiDtoMapper.toOrder(request);
        order.setUserId(principal.getUserId());
        String orderId = orderStateService.createOrder(order);
        rabbitMQService.sendOrderTimeoutMessage(orderId, timeoutThreshold);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderCreatedResponse(orderId, OrderStatus.PENDING));
    }

    @Operation(
            summary = "List the current User's Orders",
            description = "Stable keyset pagination ordered by createdAt descending, then orderId descending"
    )
    @GetMapping
    public ResponseEntity<CursorPageResponse<OrderResponse>> getOrders(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo
    ) {
        validateTimeRange(createdFrom, createdTo);
        CursorSlice<Order> slice = orderService.getUserOrdersByCursor(
                principal.getUserId(),
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

    private void validateTimeRange(Instant createdFrom, Instant createdTo) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw ApiException.badRequest(
                    "TIME_RANGE_INVALID",
                    "createdFrom must be before or equal to createdTo"
            );
        }
    }
}
