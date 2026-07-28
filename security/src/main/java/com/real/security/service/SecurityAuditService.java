package com.real.security.service;

import com.real.common.api.RequestContext;
import com.real.common.audit.AgentDelegationAuditState;
import com.real.common.audit.AuditAction;
import com.real.common.audit.AuditActor;
import com.real.common.audit.AuditActorType;
import com.real.common.audit.AuditEvent;
import com.real.common.audit.AuditResource;
import com.real.common.audit.AuditResourceType;
import com.real.common.audit.AuditResult;
import com.real.common.audit.AuditSource;
import com.real.common.audit.AuthenticationAuditState;
import com.real.common.audit.RefreshReuseAuditState;
import com.real.security.audit.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SecurityAuditService {
    private final AuditLogWriter auditLogWriter;

    public SecurityAuditService(AuditLogWriter auditLogWriter) {
        this.auditLogWriter = auditLogWriter;
    }

    public void loginSucceeded(
            String actorType,
            long userId,
            String authenticationDomain,
            HttpServletRequest request
    ) {
        auditLogWriter.append(event(
                AuditActor.identified(AuditActorType.valueOf(actorType), userId),
                null,
                AuditAction.AUTHENTICATION_LOGIN,
                new AuditResource(AuditResourceType.AUTHENTICATION_SESSION, null),
                AuditResult.SUCCESS,
                source(authenticationDomain),
                AuthenticationAuditState.succeeded(authenticationDomain),
                request
        ));
    }

    public void loginFailed(
            String authenticationDomain,
            String usernameHash,
            HttpServletRequest request
    ) {
        auditLogWriter.appendFailure(event(
                AuditActor.system(),
                null,
                AuditAction.AUTHENTICATION_LOGIN,
                new AuditResource(AuditResourceType.AUTHENTICATION_SESSION, null),
                AuditResult.DENIED,
                source(authenticationDomain),
                AuthenticationAuditState.denied(
                        authenticationDomain,
                        usernameHash,
                        "INVALID_CREDENTIALS"
                ),
                request
        ));
    }

    public void refreshReuse(
            String actorType,
            long userId,
            String familyId,
            HttpServletRequest request
    ) {
        auditLogWriter.append(event(
                AuditActor.identified(AuditActorType.valueOf(actorType), userId),
                null,
                AuditAction.REFRESH_TOKEN_REUSE_DETECTED,
                AuditResource.identified(AuditResourceType.REFRESH_TOKEN_FAMILY, familyId),
                AuditResult.DENIED,
                "ADMIN".equals(actorType) ? AuditSource.ADMIN_API : AuditSource.PORTAL_API,
                new RefreshReuseAuditState("ROTATED_TOKEN_REUSED", true),
                request
        ));
    }

    public void agentDelegationIssued(
            String serviceClientId,
            long delegatedUserId,
            int scopeCount,
            HttpServletRequest request
    ) {
        auditLogWriter.append(event(
                AuditActor.identified(AuditActorType.SERVICE, serviceClientId),
                AuditActor.identified(AuditActorType.USER, delegatedUserId),
                AuditAction.AGENT_DELEGATION_ISSUED,
                AuditResource.identified(AuditResourceType.USER, delegatedUserId),
                AuditResult.SUCCESS,
                AuditSource.AGENT_API,
                new AgentDelegationAuditState(scopeCount),
                request
        ));
    }

    private AuditEvent event(
            AuditActor actor,
            AuditActor delegatedActor,
            AuditAction action,
            AuditResource resource,
            AuditResult result,
            AuditSource source,
            com.real.common.audit.AuditStateSummary state,
            HttpServletRequest request
    ) {
        return new AuditEvent(
                actor,
                delegatedActor,
                action,
                resource,
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                source,
                Instant.now(),
                state
        );
    }

    private AuditSource source(String authenticationDomain) {
        return "ADMIN".equals(authenticationDomain)
                ? AuditSource.ADMIN_API
                : AuditSource.PORTAL_API;
    }
}
