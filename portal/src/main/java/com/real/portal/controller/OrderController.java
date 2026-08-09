package com.real.portal.controller;

import com.real.common.api.CursorSlice;
import com.real.common.api.ApiException;
import com.real.common.api.RequestContext;
import com.real.common.api.dto.CreateOrderRequest;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.OrderCreatedResponse;
import com.real.common.api.dto.OrderResponse;
import com.real.common.api.dto.TransactionTimelineEventResponse;
import com.real.common.enums.OrderStatus;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Order;
import com.real.domain.service.OrderService;
import com.real.portal.timeline.TransactionTimelineService;
import com.real.portal.userjourney.IdempotentOrderCreationService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.UserTransactionRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@Validated
@Tag(name = "User orders", description = "Orders owned by the authenticated User")
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {
    private final OrderService orderService;
    private final IdempotentOrderCreationService orderCreationService;
    private final TransactionTimelineService timelineService;
    private final UserTransactionRateLimiter transactionRateLimiter;

    public OrderController(
            OrderService orderService,
            IdempotentOrderCreationService orderCreationService,
            TransactionTimelineService timelineService,
            UserTransactionRateLimiter transactionRateLimiter
    ) {
        this.orderService = orderService;
        this.orderCreationService = orderCreationService;
        this.timelineService = timelineService;
        this.transactionRateLimiter = transactionRateLimiter;
    }

    @Operation(
            summary = "Create an Order",
            description = "Creates an Order synchronously with durable Idempotency-Key replay."
    )
    @ApiResponse(
            responseCode = "201",
            description = "Order created for the first submission",
            content = @Content(
                    mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OrderCreatedResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "200",
            description = "Persisted result replayed for the same Idempotency-Key and request",
            headers = @Header(
                    name = "Idempotency-Replayed",
                    description = "Always true for an idempotent replay",
                    required = true,
                    schema = @Schema(type = "boolean")
            ),
            content = @Content(
                    mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OrderCreatedResponse.class)
            )
    )
    @ApiResponse(
            responseCode = "409",
            description = "The Idempotency-Key was already used with a different request",
            content = @Content(
                    mediaType = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(ref = "#/components/schemas/ApiProblem")
            )
    )
    @PostMapping
    public ResponseEntity<OrderCreatedResponse> createOrder(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$")
            String idempotencyKey,
            @RequestBody @Valid CreateOrderRequest request,
            HttpServletRequest servletRequest
    ) {
        transactionRateLimiter.beforeOrder(principal.getUserId());
        IdempotentOrderCreationService.Result result = orderCreationService.create(
                principal.getUserId(),
                request,
                idempotencyKey,
                RequestContext.requestId(servletRequest)
        );
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                result.replayed() ? HttpStatus.OK : HttpStatus.CREATED
        );
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(new OrderCreatedResponse(
                result.orderId(), result.status(), result.requestId(), result.replayed()
        ));
    }

    @Operation(summary = "Get my Order", description = "Unknown and other-User Orders share 404 semantics")
    @GetMapping("/{orderId}")
    public OrderResponse getOrder(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderId
    ) {
        Order order = orderService.getOwnedOrderById(orderId, principal.getUserId());
        if (order == null) {
            throw ApiException.notFound("Order");
        }
        return ApiDtoMapper.toOrderResponse(order);
    }

    @Operation(summary = "Get my durable Order timeline")
    @GetMapping("/{orderId}/timeline")
    public List<TransactionTimelineEventResponse> timeline(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderId,
            @RequestParam(defaultValue = "0") @Min(0) long afterEventId
    ) {
        return timelineService.orderEvents(orderId, principal.getUserId(), afterEventId);
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
