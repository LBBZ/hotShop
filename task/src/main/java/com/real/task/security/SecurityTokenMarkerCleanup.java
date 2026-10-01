package com.real.task.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SecurityTokenMarkerCleanup {
    private static final Logger log = LoggerFactory.getLogger(SecurityTokenMarkerCleanup.class);
    private final JdbcTemplate jdbc;

    public SecurityTokenMarkerCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Bound each background cleanup transaction; a one-minute cadence avoids an hourly backlog.
    @Scheduled(scheduler = "maintenanceScheduler",
            fixedDelayString = "${hotshop.security.marker-cleanup-interval:1m}",
            initialDelayString = "${hotshop.security.marker-cleanup-initial-delay:1m}")
    public void removeExpiredMarkers() {
        try {
            jdbc.update("""
                    DELETE FROM security_token_marker
                     WHERE expires_at < UTC_TIMESTAMP(6)
                     ORDER BY expires_at LIMIT 10000
                    """);
        } catch (RuntimeException failure) {
            log.warn("Expired security token marker cleanup deferred; category={}",
                    failure.getClass().getSimpleName());
        }
    }
}
