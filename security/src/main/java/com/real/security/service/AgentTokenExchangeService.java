package com.real.security.service;

import com.real.common.api.ApiException;
import com.real.common.api.dto.AgentTokenExchangeRequest;
import com.real.common.api.dto.AgentTokenExchangeResponse;
import com.real.security.identity.IdentityType;
import com.real.security.identity.IssuedAccessToken;
import com.real.security.identity.ValidatedClientAssertion;
import com.real.security.identity.ValidatedToken;
import com.real.security.util.JwtTokenUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AgentTokenExchangeService {
    public static final Set<String> ALLOWED_SCOPES = Set.of(
            "catalog:read",
            "orders:self:read",
            "reservations:self:read"
    );

    private final JwtTokenUtil jwtTokenUtil;
    private final ClientAssertionReplayService replayService;
    private final TokenBlacklistService blacklistService;
    private final SecurityAuditService auditService;

    public AgentTokenExchangeService(
            JwtTokenUtil jwtTokenUtil,
            ClientAssertionReplayService replayService,
            TokenBlacklistService blacklistService,
            SecurityAuditService auditService
    ) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.replayService = replayService;
        this.blacklistService = blacklistService;
        this.auditService = auditService;
    }

    public AgentTokenExchangeResponse exchange(
            AgentTokenExchangeRequest request,
            HttpServletRequest servletRequest
    ) {
        Set<String> requestedScopes = Set.copyOf(request.scopes());
        if (requestedScopes.isEmpty() || !ALLOWED_SCOPES.containsAll(requestedScopes)) {
            throw ApiException.forbidden(
                    "AGENT_SCOPE_NOT_ALLOWED",
                    "One or more requested Agent scopes are not allowed"
            );
        }
        try {
            ValidatedClientAssertion assertion =
                    jwtTokenUtil.validateClientAssertion(request.clientAssertion());
            ValidatedToken subject = jwtTokenUtil.validate(
                    request.subjectToken(),
                    IdentityType.USER_ACCESS
            );
            if (blacklistService.isBlacklisted(subject.jti())) {
                throw new BadCredentialsException("Invalid subject token");
            }
            replayService.consumeOnce(assertion.jti(), assertion.expiresAt());
            IssuedAccessToken delegation = jwtTokenUtil.issueAgentDelegation(
                    subject.subjectUserId(),
                    subject.username(),
                    assertion.clientId(),
                    requestedScopes
            );
            auditService.agentDelegationIssued(
                    assertion.clientId(),
                    subject.subjectUserId(),
                    requestedScopes.size(),
                    servletRequest
            );
            return new AgentTokenExchangeResponse(
                    "Bearer",
                    delegation.value(),
                    delegation.expiresAt(),
                    requestedScopes
            );
        } catch (ApiException | BadCredentialsException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw ApiException.serviceUnavailable(
                    "AUTHENTICATION_SERVICE_UNAVAILABLE",
                    "Authentication services are temporarily unavailable"
            );
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid token exchange credentials");
        }
    }
}
