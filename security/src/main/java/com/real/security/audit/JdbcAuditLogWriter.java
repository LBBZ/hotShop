package com.real.security.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.audit.AuditActor;
import com.real.common.audit.AuditEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

@Service
public class JdbcAuditLogWriter implements AuditLogWriter {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditSensitiveDataSanitizer sanitizer;

    public JdbcAuditLogWriter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AuditSensitiveDataSanitizer sanitizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void append(AuditEvent event) {
        insert(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendFailure(AuditEvent event) {
        insert(event);
    }

    private void insert(AuditEvent event) {
        AuditActor delegatedActor = event.delegatedActor();
        jdbcTemplate.update(
                """
                INSERT INTO audit_log (
                    actor_type, actor_id, delegated_actor_type, delegated_actor_id,
                    action, resource_type, resource_id, result, request_id, trace_id,
                    source, occurred_at, state_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.actor().type().name(),
                event.actor().id(),
                delegatedActor == null ? null : delegatedActor.type().name(),
                delegatedActor == null ? null : delegatedActor.id(),
                event.action().name(),
                event.resource().type().name(),
                event.resource().id(),
                event.result().name(),
                event.requestId(),
                event.traceId(),
                event.source().name(),
                Timestamp.from(event.occurredAt()),
                toJson(sanitizer.sanitize(event.stateSummary()))
        );
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize sanitized audit summary", exception);
        }
    }
}
