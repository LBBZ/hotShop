package com.real.domain.agenttools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
public class AgentToolAuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentToolAuditWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void appendAgentTool(
            String agentClientId,
            long delegatedUserId,
            String tool,
            String resourceType,
            String resourceId,
            String result,
            Map<String, Object> parameterSummary,
            HttpServletRequest request
    ) {
        append(
                "AGENT",
                agentClientId,
                "USER",
                Long.toString(delegatedUserId),
                "AGENT_TOOL_INVOKED",
                resourceType,
                resourceId,
                result,
                Map.of(
                        "schemaVersion", 1,
                        "tool", tool,
                        "parameterSummary", Map.copyOf(parameterSummary)
                ),
                request
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendAgentToolFailure(
            String agentClientId,
            long delegatedUserId,
            String tool,
            String resourceType,
            String resourceId,
            String result,
            Map<String, Object> parameterSummary,
            HttpServletRequest request
    ) {
        appendAgentTool(
                agentClientId,
                delegatedUserId,
                tool,
                resourceType,
                resourceId,
                result,
                parameterSummary,
                request
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void appendConfirmation(
            long userId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            Map<String, Object> safeSummary,
            HttpServletRequest request
    ) {
        append(
                "USER", Long.toString(userId), null, null, action,
                resourceType, resourceId, result, safeSummary, request
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendConfirmationFailure(
            long userId,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> safeSummary,
            HttpServletRequest request
    ) {
        appendConfirmation(userId, action, resourceType, resourceId, "DENIED", safeSummary, request);
    }

    private void append(
            String actorType,
            String actorId,
            String delegatedActorType,
            String delegatedActorId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            Map<String, Object> summary,
            HttpServletRequest request
    ) {
        jdbc.update("""
                INSERT INTO audit_log (
                    actor_type, actor_id, delegated_actor_type, delegated_actor_id,
                    action, resource_type, resource_id, result, request_id, trace_id,
                    source, occurred_at, state_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'AGENT_API', ?, ?)
                """,
                actorType,
                actorId,
                delegatedActorType,
                delegatedActorId,
                action,
                resourceType,
                resourceId,
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                Timestamp.from(Instant.now()),
                toJson(summary)
        );
    }

    private String toJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit summary", exception);
        }
    }
}
