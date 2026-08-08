package com.real.task.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class TaskBacklogMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    public TaskBacklogMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("hotshop.outbox.backlog", backlog);
        meters.gauge("hotshop.outbox.oldest.age.seconds", oldestAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${hotshop.observability.gauge-refresh:10s}")
    public void refresh() {
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE status IN ('NEW','PUBLISHING','FAILED')",
                    Long.class
            );
            Long age = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(6))), 0)
                      FROM outbox_event
                     WHERE status IN ('NEW','PUBLISHING','FAILED')
                    """, Long.class);
            backlog.set(count == null ? 0 : Math.max(0, count));
            oldestAgeSeconds.set(age == null ? 0 : Math.max(0, age));
        } catch (DataAccessException unavailable) {
            // Preserve the last successful sample; target/DB health metrics expose the outage.
        }
    }
}
