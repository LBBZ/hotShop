package com.real.domain.messaging;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxMapper {
    String COLUMNS = """
        outbox_id, event_id, aggregate_type, aggregate_id, event_type,
        CAST(payload AS CHAR) payload, status, publish_attempts, consecutive_attempts,
        lease_token, lease_expires_at, version, created_at
        """;

    @Insert("""
        INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload)
        VALUES(#{eventId}, #{aggregateType}, #{aggregateId}, #{eventType}, CAST(#{payload} AS JSON))
        """)
    int insert(@Param("eventId") String eventId, @Param("aggregateType") String aggregateType,
               @Param("aggregateId") String aggregateId, @Param("eventType") String eventType,
               @Param("payload") String payload);

    @Select("""
        SELECT
        """ + COLUMNS + """
          FROM outbox_event
         WHERE (status='NEW' AND available_at<=#{now})
            OR (status='PUBLISHING' AND lease_expires_at<=#{now})
         ORDER BY available_at, outbox_id
         LIMIT #{limit} FOR UPDATE SKIP LOCKED
        """)
    List<OutboxEvent> lockClaimable(@Param("limit") int limit, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE outbox_event SET status='PUBLISHING', lease_token=#{token}, lease_expires_at=#{leaseUntil},
          publish_attempts=publish_attempts+1, consecutive_attempts=consecutive_attempts+1,
          version=version+1, failure_category=NULL, last_error=NULL
        WHERE outbox_id=#{id} AND version=#{version} AND consecutive_attempts<#{maxAttempts}
          AND ((status='NEW' AND available_at<=#{now})
            OR (status='PUBLISHING' AND lease_expires_at<=#{now}))
        """)
    int claim(@Param("id") long id, @Param("version") long version, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("maxAttempts") int maxAttempts);

    @Update("""
        UPDATE outbox_event SET status='FAILED', lease_token=NULL, lease_expires_at=NULL,
          failure_category='MAX_ATTEMPTS_EXHAUSTED', last_error='MAX_ATTEMPTS_EXHAUSTED', version=version+1
        WHERE outbox_id=#{id} AND status='PUBLISHING' AND lease_token=#{token}
          AND version=#{version} AND lease_expires_at<=#{now} AND consecutive_attempts>=#{maxAttempts}
        """)
    int failExpiredExhausted(@Param("id") long id, @Param("token") String token,
            @Param("version") long version, @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts);

    @Select("SELECT " + COLUMNS + " FROM outbox_event WHERE outbox_id=#{id}")
    OutboxEvent find(long id);

    @Update("""
        UPDATE outbox_event SET status='PUBLISHED', published_at=#{now}, lease_token=NULL,
          lease_expires_at=NULL, failure_category=NULL, last_error=NULL, version=version+1
        WHERE outbox_id=#{id} AND status='PUBLISHING' AND lease_token=#{token} AND version=#{version}
        """)
    int published(@Param("id") long id, @Param("token") String token,
                  @Param("version") long version, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE outbox_event SET status=#{status}, available_at=#{availableAt}, lease_token=NULL,
          lease_expires_at=NULL, failure_category=#{category}, last_error=#{category}, version=version+1
        WHERE outbox_id=#{id} AND status='PUBLISHING' AND lease_token=#{token} AND version=#{version}
        """)
    int recordFailure(@Param("id") long id, @Param("token") String token,
          @Param("version") long version, @Param("status") String status,
          @Param("availableAt") LocalDateTime availableAt, @Param("category") String category);
}
