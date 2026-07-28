package com.real.security.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SecurityAuditService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SecurityAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void loginSucceeded(
            String actorType,
            long userId,
            String authenticationDomain,
            HttpServletRequest request
    ) {
        append(
                actorType,
                Long.toString(userId),
                "AUTHENTICATION_LOGIN",
                "AUTHENTICATION_SESSION",
                null,
                "SUCCESS",
                Map.of("authenticationDomain", authenticationDomain),
                request
        );
    }

    public void loginFailed(
            String authenticationDomain,
            String usernameHash,
            HttpServletRequest request
    ) {
        append(
                "SYSTEM",
                null,
                "AUTHENTICATION_LOGIN",
                "AUTHENTICATION_SESSION",
                null,
                "DENIED",
                Map.of(
                        "authenticationDomain", authenticationDomain,
                        "usernameHash", usernameHash,
                        "reason", "INVALID_CREDENTIALS"
                ),
                request
        );
    }

    public void refreshReuse(
            String actorType,
            long userId,
            String familyId,
            HttpServletRequest request
    ) {
        append(
                actorType,
                Long.toString(userId),
                "REFRESH_TOKEN_REUSE_DETECTED",
                "REFRESH_TOKEN_FAMILY",
                familyId,
                "DENIED",
                Map.of(
                        "reason", "ROTATED_TOKEN_REUSED",
                        "familyRevoked", true
                ),
                request
        );
    }

    public void agentDelegationIssued(
            String serviceClientId,
            long delegatedUserId,
            int scopeCount,
            HttpServletRequest request
    ) {
        append(
                "SERVICE",
                serviceClientId,
                "AGENT_DELEGATION_ISSUED",
                "USER",
                Long.toString(delegatedUserId),
                "SUCCESS",
                Map.of("scopeCount", scopeCount),
                request
        );
    }

    private void append(
            String actorType,
            String actorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            Map<String, Object> state,
            HttpServletRequest request
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_log (
                    actor_type, actor_id, action, resource_type, resource_id,
                    result, request_id, trace_id, state_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                actorType,
                actorId,
                action,
                resourceType,
                resourceId,
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                toJson(state)
        );
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize security audit summary", exception);
        }
    }
}
