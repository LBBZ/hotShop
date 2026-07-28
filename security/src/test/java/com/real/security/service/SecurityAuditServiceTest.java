package com.real.security.service;

import com.real.common.api.RequestContext;
import com.real.common.audit.AuditActorType;
import com.real.common.audit.AuditResult;
import com.real.common.audit.AuditSource;
import com.real.security.audit.AuditLogWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SecurityAuditServiceTest {
    private AuditLogWriter writer;
    private SecurityAuditService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        writer = mock(AuditLogWriter.class);
        service = new SecurityAuditService(writer);
        request = new MockHttpServletRequest();
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "security-audit-test");
        request.setAttribute(
                RequestContext.TRACE_ID_ATTRIBUTE,
                "0123456789abcdef0123456789abcdef"
        );
    }

    @Test
    void agentDelegationCapturesServiceAndDelegatedUser() {
        service.agentDelegationIssued("hotshop-agent-service", 91L, 2, request);

        ArgumentCaptor<com.real.common.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.real.common.audit.AuditEvent.class);
        verify(writer).append(event.capture());
        assertThat(event.getValue().actor().type()).isEqualTo(AuditActorType.SERVICE);
        assertThat(event.getValue().actor().id()).isEqualTo("hotshop-agent-service");
        assertThat(event.getValue().delegatedActor().type()).isEqualTo(AuditActorType.USER);
        assertThat(event.getValue().delegatedActor().id()).isEqualTo("91");
        assertThat(event.getValue().source()).isEqualTo(AuditSource.AGENT_API);
    }

    @Test
    void loginFailureUsesIndependentFailureWriterAndAdminSource() {
        service.loginFailed("ADMIN", "username-sha256", request);

        ArgumentCaptor<com.real.common.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.real.common.audit.AuditEvent.class);
        verify(writer).appendFailure(event.capture());
        assertThat(event.getValue().actor().type()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(event.getValue().result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.getValue().source()).isEqualTo(AuditSource.ADMIN_API);
    }

    @Test
    void refreshReuseStaysInFamilyRevocationTransaction() {
        service.refreshReuse("USER", 44L, "family-id", request);

        ArgumentCaptor<com.real.common.audit.AuditEvent> event =
                ArgumentCaptor.forClass(com.real.common.audit.AuditEvent.class);
        verify(writer).append(event.capture());
        assertThat(event.getValue().actor().id()).isEqualTo("44");
        assertThat(event.getValue().result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.getValue().source()).isEqualTo(AuditSource.PORTAL_API);
        assertThat(event.getValue().resource().id()).isEqualTo("family-id");
    }
}
