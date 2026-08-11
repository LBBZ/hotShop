package com.real.admin.agenttools;

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
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AdminAgentToolAuditService {
    private static final String ACTION = "AGENT_TOOL_INVOKED";
    private static final String SOURCE = "AGENT_API";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminAgentToolAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendSuccess(
            long administratorId,
            String toolName,
            String resourceType,
            String resourceId,
            String parameterSummary,
            HttpServletRequest request
    ) {
        append(
                administratorId,
                toolName,
                resourceType,
                resourceId,
                "SUCCESS",
                "COMPLETED",
                parameterSummary,
                request
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendRejected(
            long administratorId,
            String toolName,
            String resourceType,
            String resourceId,
            String outcomeCode,
            String parameterSummary,
            HttpServletRequest request
    ) {
        append(
                administratorId,
                toolName,
                resourceType,
                resourceId,
                "DENIED",
                outcomeCode,
                parameterSummary,
                request
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendFailure(
            long administratorId,
            String toolName,
            String resourceType,
            String resourceId,
            String parameterSummary,
            HttpServletRequest request
    ) {
        append(
                administratorId,
                toolName,
                resourceType,
                resourceId,
                "FAILURE",
                "INTERNAL_FAILURE",
                parameterSummary,
                request
        );
    }

    private void append(
            long administratorId,
            String toolName,
            String resourceType,
            String resourceId,
            String result,
            String outcomeCode,
            String parameterSummary,
            HttpServletRequest request
    ) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("toolName", toolName);
        summary.put("outcomeCode", outcomeCode);
        summary.put("parameterSummary", parameterSummary);
        jdbcTemplate.update(
                """
                INSERT INTO audit_log (
                    actor_type, actor_id, delegated_actor_type, delegated_actor_id,
                    action, resource_type, resource_id, result, request_id, trace_id,
                    source, occurred_at, state_summary
                ) VALUES ('ADMIN', ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Long.toString(administratorId),
                ACTION,
                resourceType,
                resourceId,
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                SOURCE,
                Timestamp.from(Instant.now()),
                json(summary)
        );
    }

    private String json(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize Agent tool audit summary", exception);
        }
    }
}
