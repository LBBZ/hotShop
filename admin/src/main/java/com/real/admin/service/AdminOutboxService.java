package com.real.admin.service;

import com.real.common.api.CursorCodec;
import com.real.common.api.RequestContext;
import com.real.common.api.dto.OutboxFailedEventResponse;
import com.real.common.api.dto.OutboxFailedPageResponse;
import com.real.common.audit.*;
import com.real.security.audit.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class AdminOutboxService {
    private static final String CURSOR_SCOPE = "admin-failed-outbox";
    private final JdbcTemplate jdbc;
    private final AuditLogWriter auditLogWriter;

    public AdminOutboxService(JdbcTemplate jdbc, AuditLogWriter auditLogWriter) {
        this.jdbc = jdbc;
        this.auditLogWriter = auditLogWriter;
    }

    public OutboxFailedPageResponse failed(int limit, String cursor) {
        CursorCodec.LongCursor decoded = CursorCodec.decodeLong(cursor, CURSOR_SCOPE);
        Long before = decoded == null ? null : decoded.id();
        List<FailedRow> rows = jdbc.query("""
            SELECT outbox_id,event_id,event_type,aggregate_type,aggregate_id,publish_attempts,
                   consecutive_attempts,manual_replay_count,failure_category,created_at,updated_at
              FROM outbox_event
             WHERE status='FAILED' AND (? IS NULL OR outbox_id<?)
             ORDER BY outbox_id DESC LIMIT ?
            """, (rs, row) -> new FailedRow(
                rs.getLong("outbox_id"),
                new OutboxFailedEventResponse(
                    rs.getString("event_id"), rs.getString("event_type"),
                    rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                    rs.getInt("publish_attempts"), rs.getInt("consecutive_attempts"),
                    rs.getInt("manual_replay_count"), rs.getString("failure_category"),
                    instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))
                )), before, before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<FailedRow> page = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore ? CursorCodec.encodeLong(CURSOR_SCOPE, page.getLast().outboxId()) : null;
        return new OutboxFailedPageResponse(page.stream().map(FailedRow::response).toList(), next, hasMore);
    }

    @Transactional
    public ReplayResult replay(String eventId, String reason, long actorId, HttpServletRequest request) {
        String status = jdbc.query("SELECT status FROM outbox_event WHERE event_id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, eventId);
        if (!"FAILED".equals(status)) {
            audit(actorId, eventId, reason, AuditResult.FAILURE,
                    status == null ? "NOT_FOUND" : "NOT_FAILED_" + status, request);
            return status == null ? ReplayResult.NOT_FOUND : ReplayResult.NOT_FAILED;
        }
        int changed = jdbc.update("""
            UPDATE outbox_event
               SET status='NEW', available_at=UTC_TIMESTAMP(6), lease_token=NULL,
                   lease_expires_at=NULL, consecutive_attempts=0,
                   manual_replay_count=manual_replay_count+1, version=version+1,
                   last_error=NULL, failure_category=NULL
             WHERE event_id=? AND status='FAILED'
            """, eventId);
        if (changed != 1) throw new IllegalStateException("Outbox replay state changed while locked");
        audit(actorId, eventId, reason, AuditResult.SUCCESS, "FAILED_TO_NEW", request);
        return ReplayResult.REPLAYED;
    }

    private void audit(long actorId, String eventId, String reason, AuditResult result,
            String outcome, HttpServletRequest request) {
        auditLogWriter.append(new AuditEvent(
                AuditActor.identified(AuditActorType.ADMIN, actorId),
                null,
                AuditAction.OUTBOX_REPLAY,
                new AuditResource(AuditResourceType.OUTBOX_EVENT, eventId),
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                AuditSource.ADMIN_API,
                Instant.now(),
                new OutboxReplayAuditState(reason.strip(), outcome)
        ));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record FailedRow(long outboxId, OutboxFailedEventResponse response) { }
    public enum ReplayResult { REPLAYED, NOT_FAILED, NOT_FOUND }
}
