package com.real.admin;

import com.real.domain.entity.Product;
import com.real.domain.service.OrderService;
import com.real.domain.service.ProductService;
import com.real.domain.service.UserService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.service.TokenBlacklistService;
import com.real.security.util.JwtTokenUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class AdminApiContractTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @Test
    void administratorCanReadProductDto() throws Exception {
        when(productService.getProductById(55L)).thenReturn(new Product(
                55L,
                "Admin product",
                new BigDecimal("88.00"),
                4,
                "Contract",
                "Admin DTO",
                LocalDateTime.of(2026, 7, 27, 11, 0)
        ));

        mockMvc.perform(get("/admin/api/v1/products/55").with(user(adminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("55"))
                .andExpect(jsonPath("$.price").value("88.00"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-27T11:00:00Z"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void authenticatedUserIsForbiddenFromAdminBoundary() throws Exception {
        mockMvc.perform(get("/admin/api/v1/products/55").with(user(userPrincipal())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void legacyAdminPathIsRemovedInTheVersionUpgrade() throws Exception {
        mockMvc.perform(get("/admin/products/55").with(user(adminPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void invalidOrderIdIsRejectedBeforeCallingTheService() throws Exception {
        mockMvc.perform(get("/admin/api/v1/orders/{orderId}", "invalid.order")
                        .with(user(adminPrincipal())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("getOrder.orderId"));
    }

    @Test
    void runtimeAdminOpenApiContainsOnlyAdminV1Boundary() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/admin/api/v1/products']").exists())
                .andExpect(jsonPath("$.paths['/admin/products/{id}']").doesNotExist())
                .andExpect(jsonPath(
                        "$.paths['/admin/api/v1/products/{productId}'].get.parameters"
                                + "[?(@.name == 'productId')].schema.type"
                ).value(org.hamcrest.Matchers.contains("string")))
                .andExpect(jsonPath(
                        "$.paths['/admin/api/v1/orders'].get.parameters"
                                + "[?(@.name == 'userId')].schema.type"
                ).value(org.hamcrest.Matchers.contains("string")))
                .andExpect(jsonPath(
                        "$.paths['/admin/api/v1/users'].get.parameters"
                                + "[?(@.name == 'userId')].schema.type"
                ).value(org.hamcrest.Matchers.contains("string")))
                .andExpect(jsonPath(
                        "$.paths['/admin/api/v1/orders/{orderId}'].get.parameters"
                                + "[?(@.name == 'orderId')].schema.pattern"
                ).value(org.hamcrest.Matchers.contains("^[A-Za-z0-9_-]{1,64}$")))
                .andExpect(jsonPath("$.components.parameters.IdempotencyKey").exists())
                .andExpect(jsonPath("$.components.schemas.ApiProblem.required").isArray())
                .andExpect(jsonPath("$.components.schemas.Product").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.User").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.Order").doesNotExist());
    }

    @Test
    void jsonMoneyRejectsNumberTokensAndKeepsBigDecimalStringContract() throws Exception {
        mockMvc.perform(post("/admin/api/v1/products")
                        .with(user(adminPrincipal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Unsafe numeric amount",
                                  "price": 88.00,
                                  "stock": 1,
                                  "category": "Contract"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    private CustomUserDetails adminPrincipal() {
        return CustomUserDetails.builder()
                .userId(100L)
                .username("contract-admin")
                .password("not-returned")
                .authorities(
                        JwtTokenUtil.administratorAuthorities().stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
                .build();
    }

    private CustomUserDetails userPrincipal() {
        return principal("contract-user", "ROLE_USER");
    }

    private CustomUserDetails principal(String username, String authority) {
        return CustomUserDetails.builder()
                .userId(100L)
                .username(username)
                .password("not-returned")
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .build();
    }
}
