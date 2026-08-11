package com.real.admin.agenttools;

import com.real.security.entity.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAgentToolsControllerTest {
    @Test
    void controllerExposesOnlyFixedPathsBehindRoleAdmin() {
        PreAuthorize authorization = AdminAgentToolsController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
        assertThat(AdminAgentToolsController.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder(
                        "statistics",
                        "anomalies",
                        "createConfigurationDraft"
                );
    }

    @Test
    void controllerPassesAdministratorIdentityAndRejectableParameterPresence() {
        AdminAgentToolService service = mock(AdminAgentToolService.class);
        AdminAgentToolsController controller = new AdminAgentToolsController(service);
        CustomUserDetails administrator = CustomUserDetails.builder()
                .userId(81L)
                .username("admin")
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("unexpected", "value");
        AdminAgentStatisticsResponse expected = new AdminAgentStatisticsResponse(
                Instant.parse("2026-08-09T06:00:00Z"),
                1,
                2,
                3,
                4,
                5
        );
        when(service.statistics(81L, true, request)).thenReturn(expected);

        assertThat(controller.statistics(administrator, request)).isSameAs(expected);
        verify(service).statistics(81L, true, request);
    }
}
