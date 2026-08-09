package com.real.portal;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.FlashSaleReservationStatusResponse;
import com.real.common.api.dto.FlashSaleActivityResponse;
import com.real.common.enums.OrderStatus;
import com.real.common.enums.Role;
import com.real.domain.entity.Order;
import com.real.domain.entity.Product;
import com.real.domain.service.OrderService;
import com.real.domain.service.ProductService;
import com.real.domain.service.UserService;
import com.real.domain.service.advance.OrderStateService;
import com.real.domain.service.seckill.FlashSaleReservationCode;
import com.real.domain.service.seckill.FlashSaleReservationResult;
import com.real.domain.service.seckill.FlashSaleReservationService;
import com.real.domain.service.seckill.FlashSaleReservationStatusService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.TokenBlacklistService;
import com.real.portal.payment.PaymentService;
import com.real.portal.payment.MockPaymentCallbackService;
import com.real.portal.sse.TransactionEventStreamService;
import com.real.portal.timeline.TransactionTimelineService;
import com.real.portal.userjourney.FlashSaleActivityQueryService;
import com.real.portal.userjourney.IdempotentOrderCreationService;
import com.real.portal.payment.CallbackRejectedException;
import com.real.security.service.UserTransactionRateLimiter;
import com.real.common.api.dto.MockPaymentCallbackResponse;
import com.real.common.api.dto.MockPaymentActionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class PortalApiContractTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void portalDoesNotCreateRabbitConnectionFactory() {
        assertThat(applicationContext.getBeansOfType(
                org.springframework.amqp.rabbit.connection.ConnectionFactory.class)).isEmpty();
    }

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private OrderStateService orderStateService;
    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;
    @MockitoBean
    private FlashSaleReservationService flashSaleReservationService;
    @MockitoBean
    private FlashSaleReservationStatusService flashSaleReservationStatusService;
    @MockitoBean
    private PaymentService paymentService;
    @MockitoBean
    private MockPaymentCallbackService mockPaymentCallbackService;
    @MockitoBean
    private FlashSaleActivityQueryService flashSaleActivityQueryService;
    @MockitoBean
    private IdempotentOrderCreationService idempotentOrderCreationService;
    @MockitoBean
    private TransactionTimelineService transactionTimelineService;
    @MockitoBean
    private TransactionEventStreamService transactionEventStreamService;
    @MockitoBean
    private UserTransactionRateLimiter userTransactionRateLimiter;

    @Test
    void anonymousActivityQueryExposesServerClockAndProductFacts() throws Exception {
        when(flashSaleActivityQueryService.currentAndUpcoming(12)).thenReturn(List.of(
                new FlashSaleActivityResponse(
                        7001L, "DROP-7001", 41L, "Contract product", "Contract",
                        "A real sale window", new BigDecimal("9.90"), 3, 1,
                        "ACTIVE", "LIVE", Instant.parse("2026-08-08T05:00:00Z"),
                        Instant.parse("2026-08-08T06:00:00Z"),
                        Instant.parse("2026-08-08T05:30:00Z")
                )
        ));

        mockMvc.perform(get("/api/v1/flash-sale-activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activityId").value("7001"))
                .andExpect(jsonPath("$[0].productId").value("41"))
                .andExpect(jsonPath("$[0].salePrice").value("9.90"))
                .andExpect(jsonPath("$[0].phase").value("LIVE"))
                .andExpect(jsonPath("$[0].serverTime").value("2026-08-08T05:30:00Z"));
    }

    @Test
    void ordinaryOrderCreationUsesDurableIdempotencyContract() throws Exception {
        when(idempotentOrderCreationService.create(
                anyLong(), any(), any(), any()
        )).thenReturn(new IdempotentOrderCreationService.Result(
                "order-idempotent", OrderStatus.PENDING, "order-request-1", true
        ));

        mockMvc.perform(post("/api/v1/orders")
                        .with(user(userPrincipal()))
                        .header("X-Request-Id", "order-request-1")
                        .header("Idempotency-Key", "order-key-000000000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"41\",\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value("order-idempotent"))
                .andExpect(jsonPath("$.requestId").value("order-request-1"))
                .andExpect(jsonPath("$.idempotencyReplayed").value(true));
    }

    @Test
    void ordinaryOrderFirstCreationReturns201WithoutReplayHeader() throws Exception {
        when(idempotentOrderCreationService.create(anyLong(), any(), any(), any()))
                .thenReturn(new IdempotentOrderCreationService.Result(
                        "order-created", OrderStatus.PENDING, "original-request-id", false
                ));

        mockMvc.perform(post("/api/v1/orders")
                        .with(user(userPrincipal()))
                        .header("X-Request-Id", "original-request-id")
                        .header("Idempotency-Key", "order-key-000000000000000003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"41\",\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value("order-created"))
                .andExpect(jsonPath("$.requestId").value("original-request-id"))
                .andExpect(jsonPath("$.idempotencyReplayed").value(false));
    }

    @Test
    void ordinaryOrderChangedFingerprintReturns409ApiProblem() throws Exception {
        when(idempotentOrderCreationService.create(anyLong(), any(), any(), any()))
                .thenThrow(ApiException.conflict(
                        "IDEMPOTENCY_KEY_CONFLICT",
                        "Idempotency-Key is already bound to a different order request"
                ));

        mockMvc.perform(post("/api/v1/orders")
                        .with(user(userPrincipal()))
                        .header("X-Request-Id", "conflicting-request-id")
                        .header("Idempotency-Key", "order-key-000000000000000004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":\"41\",\"quantity\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.requestId").value("conflicting-request-id"));
    }

    @Test
    void authenticatedEventStreamForwardsLastEventIdAsAHeader() throws Exception {
        SseEmitter emitter = new SseEmitter(1_000L);
        when(transactionEventStreamService.order("order-stream", 100L, 42L)).thenReturn(emitter);

        mockMvc.perform(get("/api/v1/orders/order-stream/events")
                        .with(user(userPrincipal()))
                        .header("Last-Event-ID", "42")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(transactionEventStreamService).order("order-stream", 100L, 42L);
        emitter.complete();
    }

    @Test
    void anonymousProductListReturnsDtoFormatsAndCorrelatedIds() throws Exception {
        Product product = product(41L);
        when(productService.getProductsByCursor(
                anyInt(), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenAnswer(invocation -> {
            assertThat(MDC.get("requestId")).isEqualTo("client-request-1");
            assertThat(MDC.get("traceId")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            return new CursorSlice<>(List.of(product), null, false);
        });

        mockMvc.perform(get("/api/v1/products")
                        .param("limit", "2")
                        .header("X-Request-Id", "client-request-1")
                        .header(
                                "traceparent",
                                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "client-request-1"))
                .andExpect(header().string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.items[0].productId").value("41"))
                .andExpect(jsonPath("$.items[0].price").value("19.90"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-07-27T09:00:00Z"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].password").doesNotExist());
    }

    @Test
    void missingAuthenticationUsesProblemDetailsAndSameRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/orders").header("X-Request-Id", "missing-auth-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("X-Request-Id", "missing-auth-1"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/orders"))
                .andExpect(jsonPath("$.requestId").value("missing-auth-1"))
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void flashSaleReservationRequiresUserIdempotencyAndReturnsAcceptedReplayContract()
            throws Exception {
        CustomUserDetails principal = CustomUserDetails.builder()
                .userId(77L)
                .username("reservation-user")
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        when(flashSaleReservationService.reserve(
                901L,
                77L,
                2,
                "reservation-key-000000000000000000000001",
                "reservation-request-1"
        )).thenReturn(new FlashSaleReservationResult(
                FlashSaleReservationCode.ACCEPTED,
                "rsv_0123456789abcdef0123456789abcdef",
                901L,
                "RESERVED",
                "reservation-request-1",
                "1-0"
        ));

        mockMvc.perform(post("/api/v1/flash-sales/{activityId}/reservations", 901)
                        .with(user(principal))
                        .header("X-Request-Id", "reservation-request-1")
                        .header(
                                "Idempotency-Key",
                                "reservation-key-000000000000000000000001"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reservationNo")
                        .value("rsv_0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.activityId").value("901"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.requestId").value("reservation-request-1"));

        verify(flashSaleReservationService).reserve(
                901L,
                77L,
                2,
                "reservation-key-000000000000000000000001",
                "reservation-request-1"
        );
    }

    @Test
    void flashSaleReservationRejectsMissingIdempotencyAndChangedFingerprint() throws Exception {
        CustomUserDetails principal = CustomUserDetails.builder()
                .userId(78L)
                .username("reservation-user-2")
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        mockMvc.perform(post("/api/v1/flash-sales/{activityId}/reservations", 902)
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PARAMETER_MISSING"))
                .andExpect(jsonPath("$.violations[0].field").value("Idempotency-Key"));

        when(flashSaleReservationService.reserve(
                902L,
                78L,
                2,
                "conflicting-key-0000000000000000000000001",
                "conflict-request"
        )).thenReturn(new FlashSaleReservationResult(
                FlashSaleReservationCode.IDEMPOTENCY_CONFLICT,
                null,
                902L,
                null,
                null,
                null
        ));
        mockMvc.perform(post("/api/v1/flash-sales/{activityId}/reservations", 902)
                        .with(user(principal))
                        .header("X-Request-Id", "conflict-request")
                        .header(
                                "Idempotency-Key",
                                "conflicting-key-0000000000000000000000001"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void flashSaleReservationStatusReturnsOnlyTheAuthenticatedUsersFinalFacts() throws Exception {
        String reservationNo = "rsv_0123456789abcdef0123456789abcdef";
        when(flashSaleReservationStatusService.findOwned(901L, reservationNo, 100L))
                .thenReturn(new FlashSaleReservationStatusResponse(
                        reservationNo,
                        901L,
                        "ORDER_CREATED",
                        "ord_0123456789abcdef0123456789abcdef",
                        2,
                        new BigDecimal("39.80"),
                        "CNY"
                ));

        mockMvc.perform(get(
                                "/api/v1/flash-sales/{activityId}/reservations/{reservationNo}",
                                901,
                                reservationNo
                        )
                        .with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationNo").value(reservationNo))
                .andExpect(jsonPath("$.activityId").value("901"))
                .andExpect(jsonPath("$.status").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.orderId")
                        .value("ord_0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.reservedAmount").value("39.80"))
                .andExpect(jsonPath("$.currency").value("CNY"));

        verify(flashSaleReservationStatusService).findOwned(901L, reservationNo, 100L);
    }

    @Test
    void flashSaleReservationStatusHidesUnknownAndOtherUsersReservations() throws Exception {
        String reservationNo = "rsv_fedcba9876543210fedcba9876543210";
        when(flashSaleReservationStatusService.findOwned(902L, reservationNo, 100L))
                .thenReturn(null);

        mockMvc.perform(get(
                                "/api/v1/flash-sales/{activityId}/reservations/{reservationNo}",
                                902,
                                reservationNo
                        )
                        .with(user(userPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Flash Sale Reservation was not found"));

        verify(flashSaleReservationStatusService).findOwned(902L, reservationNo, 100L);
    }

    @Test
    void flashSaleReservationStatusRequiresRoleUser() throws Exception {
        CustomUserDetails admin = CustomUserDetails.builder()
                .userId(1L)
                .username("reservation-admin")
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .build();

        mockMvc.perform(get(
                                "/api/v1/flash-sales/{activityId}/reservations/{reservationNo}",
                                901,
                                "rsv_0123456789abcdef0123456789abcdef"
                        )
                        .with(user(admin)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unsupportedMethodUsesProblemDetailsAndPreservesAllowHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").header("X-Request-Id", "method-405"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/login"))
                .andExpect(jsonPath("$.requestId").value("method-405"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unsupportedRequestMediaTypeUsesProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/register"));
    }

    @Test
    void unacceptableResponseMediaTypeUsesProblemDetails() throws Exception {
        when(productService.getProductById(41L)).thenReturn(product(41L));

        mockMvc.perform(get("/api/v1/products/41").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/41"));
    }

    @Test
    void authenticatedUserOrderListUsesUserBoundary() throws Exception {
        Order order = new Order(
                "order_100",
                100L,
                new BigDecimal("39.80"),
                OrderStatus.PENDING,
                LocalDateTime.of(2026, 7, 27, 9, 30),
                List.of()
        );
        when(orderService.getUserOrdersByCursor(
                anyLong(), anyInt(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(new CursorSlice<>(List.of(order), null, false));

        mockMvc.perform(get("/api/v1/orders").with(user(userPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value("order_100"))
                .andExpect(jsonPath("$.items[0].userId").value("100"))
                .andExpect(jsonPath("$.items[0].totalAmount").value("39.80"))
                .andExpect(jsonPath("$.items[0].currency").value("CNY"));
    }

    @Test
    void invalidParameterProvidesStructuredViolations() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("getProducts.limit"))
                .andExpect(jsonPath("$.violations[0].code").value("Min"));
    }

    @Test
    void jsonIdsRejectFloatingPointProneNumberTokens() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .with(user(userPrincipal()))
                        .header("Idempotency-Key", "order-key-000000000000000002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [
                                    {"productId": 456, "quantity": 1}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(content().string(not(containsString("LongIdStringDeserializer"))));
    }

    @Test
    void missingResourceUsesSanitizedProblemDetails() throws Exception {
        when(productService.getProductById(404L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/products/404"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("com.real"))))
                .andExpect(content().string(not(containsString("SELECT"))));
    }

    @Test
    void registrationConflictHasStableBusinessCode() throws Exception {
        when(userService.userExistsByUsername("taken-user")).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "taken-user",
                                  "password": "Password1!",
                                  "email": "taken@hotshop.invalid"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_CONFLICT"))
                .andExpect(content().string(not(containsString("Password1!"))));
    }

    @Test
    void rateLimitMappingIncludesRetryAfter() throws Exception {
        when(productService.getProductById(429L)).thenThrow(ApiException.rateLimited(7));

        mockMvc.perform(get("/api/v1/products/429"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void unknownExceptionDoesNotLeakInternalDetails() throws Exception {
        when(productService.getProductById(500L))
                .thenThrow(new RuntimeException(
                        "SELECT password_hash FROM app_user WHERE token='secret-token'"
                ));

        mockMvc.perform(get("/api/v1/products/500"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("password_hash"))))
                .andExpect(content().string(not(containsString("secret-token"))))
                .andExpect(content().string(not(containsString("RuntimeException"))));
    }

    @Test
    void legacyPortalPathIsRemovedInTheVersionUpgrade() throws Exception {
        mockMvc.perform(get("/portal/products/all").with(user(userPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void runtimeOpenApiGroupsExposeOnlyVersionedPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/products']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/products/{productId}'].get.parameters"
                                + "[?(@.name == 'productId')].schema.type"
                ).value(org.hamcrest.Matchers.contains("string")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/products/{productId}'].get.parameters"
                                + "[?(@.name == 'productId')].schema.pattern"
                ).value(org.hamcrest.Matchers.contains("^[1-9][0-9]{0,18}$")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/auth/login'].post.responses['405']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/auth/login'].post.responses['406']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/auth/login'].post.responses['415']"
                ).exists())
                .andExpect(jsonPath("$.paths['/portal/products/all']").doesNotExist());

        mockMvc.perform(get("/v3/api-docs/user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/orders']").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post.responses['201'].content"
                                + "['application/json'].schema['$ref']"
                ).value("#/components/schemas/OrderCreatedResponse"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post.responses['200'].content"
                                + "['application/json'].schema['$ref']"
                ).value("#/components/schemas/OrderCreatedResponse"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post.responses['200'].headers"
                                + "['Idempotency-Replayed'].schema.type"
                ).value("boolean"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders'].post.responses['409'].content"
                                + "['application/problem+json'].schema['$ref']"
                ).value("#/components/schemas/ApiProblem"))
                .andExpect(jsonPath(
                        "$.components.headers.IdempotencyReplayed.schema.type"
                ).value("boolean"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/flash-sales/{activityId}/reservations']"
                                + ".post.responses['202']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/flash-sales/{activityId}/reservations']"
                                + ".post.responses['200']"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/flash-sales/{activityId}/reservations/{reservationNo}']"
                                + ".get.responses['200']"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/flash-sales/{activityId}/reservations/{reservationNo}']"
                                + ".get.responses['404']"
                ).exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/payments'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments/{paymentNo}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments/{paymentNo}/mock-actions'].post").exists())
                .andExpect(jsonPath("$.paths['/provider-callbacks/v1/mock-payment']").doesNotExist())
                .andExpect(jsonPath("$.paths['/admin/api/v1/orders']").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.Order").doesNotExist());

        mockMvc.perform(get("/v3/api-docs/agent-boundary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/agent/api/v1/fake']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/payments/{paymentNo}/mock-actions']").doesNotExist())
                .andExpect(jsonPath("$.paths['/provider-callbacks/v1/mock-payment']").doesNotExist());

        mockMvc.perform(get("/v3/api-docs/mock-provider-callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/provider-callbacks/v1/mock-payment'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments/{paymentNo}']").doesNotExist());
    }

    @Test
    void paymentWritesRequireUserAndProviderCallbackRequiresItsOwnHeaders() throws Exception {
        CustomUserDetails admin = CustomUserDetails.builder().userId(1L).username("admin").password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).build();
        CustomUserDetails agent = CustomUserDetails.builder().userId(2L).username("agent").password("")
                .authorities(List.of(new SimpleGrantedAuthority("AGENT_DELEGATION"))).build();
        CustomUserDetails regularUser = CustomUserDetails.builder().userId(3L).username("user").password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER"))).build();
        mockMvc.perform(post("/api/v1/orders/order-auth/payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/orders/order-auth/payments").with(user(admin))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/orders/order-auth/payments").with(user(agent))).andExpect(status().isForbidden());

        for (String path : List.of(
                "/api/v1/payments/MOCK_00000000000000000000000000000001",
                "/api/v1/payments/MOCK_00000000000000000000000000000001/mock-actions")) {
            var anonymous = path.endsWith("mock-actions") ? post(path) : get(path);
            var asAdmin = path.endsWith("mock-actions") ? post(path).with(user(admin)) : get(path).with(user(admin));
            var asAgent = path.endsWith("mock-actions") ? post(path).with(user(agent)) : get(path).with(user(agent));
            mockMvc.perform(anonymous).andExpect(status().isUnauthorized());
            mockMvc.perform(asAdmin).andExpect(status().isForbidden());
            mockMvc.perform(asAgent).andExpect(status().isForbidden());
        }

        when(mockPaymentCallbackService.accept(isNull(), isNull(), isNull(), any(byte[].class), any()))
                .thenThrow(new CallbackRejectedException("SIGNATURE_INVALID", HttpStatus.UNAUTHORIZED));
        mockMvc.perform(post("/provider-callbacks/v1/mock-payment")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/provider-callbacks/v1/mock-payment")
                        .with(user(regularUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/provider-callbacks/v1/mock-payment/extra")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        when(mockPaymentCallbackService.accept(any(), any(), any(), any(), any()))
                .thenReturn(new MockPaymentCallbackResponse(
                        "00000000-0000-0000-0000-000000000001", "IDEMPOTENT", true));
        mockMvc.perform(post("/provider-callbacks/v1/mock-payment")
                        .header("X-Mock-Timestamp", "1785542400")
                        .header("X-Mock-Nonce", "abcdefghijklmnop")
                        .header("X-Mock-Signature", "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true));

        String paymentNo = "MOCK_00000000000000000000000000000001";
        when(paymentService.action(anyLong(), any(), any())).thenReturn(new MockPaymentActionResponse(
                "00000000-0000-0000-0000-000000000002", "MOCK", paymentNo, "SUCCEEDED",
                Instant.parse("2026-08-01T13:00:00Z"), 1, true,
                "Mock Payment only; no real funds are transferred"));
        mockMvc.perform(post("/api/v1/payments/{paymentNo}/mock-actions", paymentNo)
                        .with(user(regularUser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"SUCCEEDED\",\"delay\":\"PT0S\",\"duplicateCount\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.provider").value("MOCK"))
                .andExpect(jsonPath("$.localDemoOnly").value(true));
    }

    private Product product(long id) {
        return new Product(
                id,
                "Contract product",
                new BigDecimal("19.90"),
                8,
                "Contract",
                "DTO boundary",
                LocalDateTime.of(2026, 7, 27, 9, 0)
        );
    }

    private CustomUserDetails userPrincipal() {
        return CustomUserDetails.builder()
                .userId(100L)
                .username("contract-user")
                .password("not-returned")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
    }
}
