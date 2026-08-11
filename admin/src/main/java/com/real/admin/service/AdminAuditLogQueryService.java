package com.real.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.real.common.api.CursorSlice;
import com.real.common.audit.AuditAction;
import com.real.common.audit.AuditActorType;
import com.real.common.audit.AuditLogResponse;
import com.real.common.audit.AuditResourceType;
import com.real.common.audit.AuditResult;
import com.real.common.audit.AuditSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class AdminAuditLogQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminCursorCodec cursors;

    public AdminAuditLogQueryService(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AdminCursorCodec cursors
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cursors = cursors;
    }

    public CursorSlice<AuditLogResponse> query(
            int limit,
            String cursor,
            Instant occurredFrom,
            Instant occurredTo,
            AuditActorType actorType,
            String actorId,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            AuditResult result
    ) {
        String scope = cursorScope(
                occurredFrom,
                occurredTo,
                actorType,
                actorId,
                action,
                resourceType,
                resourceId,
                result
        );
        AdminCursorCodec.TimeLongCursor decoded = cursors.decodeTimeLong(cursor, scope);
        StringBuilder sql = new StringBuilder("""
                SELECT
                    audit_id, actor_type, actor_id, delegated_actor_type, delegated_actor_id,
                    action, resource_type, resource_id, result, request_id, trace_id,
                    source, occurred_at, state_summary
                FROM audit_log
                WHERE 1 = 1
                """);
        List<Object> arguments = new ArrayList<>();
        addInstantFilter(sql, arguments, "occurred_at >= ?", occurredFrom);
        addInstantFilter(sql, arguments, "occurred_at <= ?", occurredTo);
        addEnumFilter(sql, arguments, "actor_type = ?", actorType);
        addStringFilter(sql, arguments, "actor_id = ?", actorId);
        addEnumFilter(sql, arguments, "action = ?", action);
        addEnumFilter(sql, arguments, "resource_type = ?", resourceType);
        addStringFilter(sql, arguments, "resource_id = ?", resourceId);
        addEnumFilter(sql, arguments, "result = ?", result);
        if (decoded != null) {
            sql.append("""
                     AND (occurred_at < ? OR (occurred_at = ? AND audit_id < ?))
                    """);
            Timestamp cursorTime = Timestamp.valueOf(decoded.time());
            arguments.add(cursorTime);
            arguments.add(cursorTime);
            arguments.add(decoded.id());
        }
        sql.append(" ORDER BY occurred_at DESC, audit_id DESC LIMIT ?");
        arguments.add(limit + 1);

        List<AuditLogResponse> fetched =
                jdbcTemplate.query(sql.toString(), this::mapRow, arguments.toArray());
        boolean hasMore = fetched.size() > limit;
        List<AuditLogResponse> items =
                hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore
                ? cursors.encodeTimeLong(
                        scope,
                        LocalDateTime.ofInstant(items.getLast().occurredAt(), ZoneOffset.UTC),
                        items.getLast().auditId()
                )
                : null;
        return new CursorSlice<>(items, nextCursor, hasMore);
    }

    private AuditLogResponse mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditLogResponse(
                resultSet.getLong("audit_id"),
                AuditActorType.valueOf(resultSet.getString("actor_type")),
                resultSet.getString("actor_id"),
                enumOrNull(AuditActorType.class, resultSet.getString("delegated_actor_type")),
                resultSet.getString("delegated_actor_id"),
                AuditAction.valueOf(resultSet.getString("action")),
                AuditResourceType.valueOf(resultSet.getString("resource_type")),
                resultSet.getString("resource_id"),
                AuditResult.valueOf(resultSet.getString("result")),
                resultSet.getString("request_id"),
                resultSet.getString("trace_id"),
                AuditSource.valueOf(resultSet.getString("source")),
                resultSet.getTimestamp("occurred_at").toLocalDateTime().toInstant(ZoneOffset.UTC),
                parseSummary(resultSet.getString("state_summary"))
        );
    }

    private Map<String, Object> parseSummary(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored audit summary is not valid JSON", exception);
        }
    }

    private <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private void addInstantFilter(
            StringBuilder sql,
            List<Object> arguments,
            String predicate,
            Instant value
    ) {
        if (value != null) {
            sql.append(" AND ").append(predicate);
            arguments.add(Timestamp.from(value));
        }
    }

    private void addEnumFilter(
            StringBuilder sql,
            List<Object> arguments,
            String predicate,
            Enum<?> value
    ) {
        if (value != null) {
            sql.append(" AND ").append(predicate);
            arguments.add(value.name());
        }
    }

    private void addStringFilter(
            StringBuilder sql,
            List<Object> arguments,
            String predicate,
            String value
    ) {
        if (value != null) {
            sql.append(" AND ").append(predicate);
            arguments.add(value);
        }
    }

    private String cursorScope(Object... filters) {
        StringBuilder canonical = new StringBuilder("audit-logs");
        for (Object filter : filters) {
            canonical.append('|').append(filter == null ? "" : filter);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "audit-logs-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
