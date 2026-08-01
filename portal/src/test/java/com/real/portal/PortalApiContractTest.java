package com.real.portal;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.FlashSaleReservationStatusResponse;
import com.real.common.enums.OrderStatus;
import com.real.common.enums.Role;
import com.real.domain.entity.Order;
import com.real.domain.entity.Product;
import com.real.domain.infra.RabbitMQService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class PortalApiContractTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private OrderStateService orderStateService;
    @MockitoBean
    private RabbitMQService rabbitMQService;
    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;
    @MockitoBean
    private FlashSaleReservationService flashSaleReservationService;
    @MockitoBean
    private FlashSaleReservationStatusService flashSaleReservationStatusService;

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
                .andExpect(jsonPath("$.paths['/admin/api/v1/orders']").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.Order").doesNotExist());

        mockMvc.perform(get("/v3/api-docs/agent-boundary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/agent/api/v1/fake']").doesNotExist());
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
