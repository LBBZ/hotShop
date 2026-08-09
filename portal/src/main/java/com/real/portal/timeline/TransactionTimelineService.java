package com.real.portal.timeline;

import com.real.common.api.ApiException;
import com.real.common.api.dto.TransactionTimelineEventResponse;
import com.real.domain.service.seckill.FlashSaleReservationStatusService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class TransactionTimelineService {
    private final JdbcTemplate jdbc;
    private final FlashSaleReservationStatusService reservationStatusService;

    public TransactionTimelineService(
            JdbcTemplate jdbc,
            FlashSaleReservationStatusService reservationStatusService
    ) {
        this.jdbc = jdbc;
        this.reservationStatusService = reservationStatusService;
    }

    public void requireOwnedReservation(long activityId, String reservationNo, long userId) {
        if (reservationStatusService.findOwned(activityId, reservationNo, userId) == null) {
            throw ApiException.notFound("Flash Sale Reservation");
        }
    }

    public void requireOwnedOrder(String orderId, long userId) {
        if (!isOwnedOrder(orderId, userId)) {
            throw ApiException.notFound("Order");
        }
    }

    public List<TransactionTimelineEventResponse> reservationEvents(
            long activityId,
            String reservationNo,
            long userId,
            long afterEventId
    ) {
        requireOwnedReservation(activityId, reservationNo, userId);
        return events(userId, "RESERVATION", reservationNo, afterEventId);
    }

    public List<TransactionTimelineEventResponse> orderEvents(
            String orderId,
            long userId,
            long afterEventId
    ) {
        requireOwnedOrder(orderId, userId);
        return events(userId, "ORDER", orderId, afterEventId);
    }

    public List<TransactionTimelineEventResponse> durableReservationEvents(
            String reservationNo,
            long userId,
            long afterEventId
    ) {
        return events(userId, "RESERVATION", reservationNo, afterEventId);
    }

    public List<TransactionTimelineEventResponse> durableOrderEvents(
            String orderId,
            long userId,
            long afterEventId
    ) {
        return events(userId, "ORDER", orderId, afterEventId);
    }

    public boolean isOwnedOrder(String orderId, long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_order WHERE order_id = ? AND user_id = ?",
                Integer.class,
                orderId,
                userId
        );
        return count != null && count == 1;
    }

    private List<TransactionTimelineEventResponse> events(
            long userId,
            String resourceType,
            String resourceId,
            long afterEventId
    ) {
        return jdbc.query("""
                SELECT event_id, resource_type, resource_id, reservation_no, order_id,
                       event_type, request_id, detail_code, occurred_at
                  FROM user_transaction_timeline
                 WHERE user_id = ?
                   AND resource_type = ?
                   AND resource_id = ?
                   AND event_id > ?
                 ORDER BY event_id ASC
                 LIMIT 200
                """, (rs, rowNum) -> new TransactionTimelineEventResponse(
                rs.getLong("event_id"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("reservation_no"),
                rs.getString("order_id"),
                rs.getString("event_type"),
                rs.getString("request_id"),
                rs.getString("detail_code"),
                timestamp(rs.getTimestamp("occurred_at"))
        ), userId, resourceType, resourceId, afterEventId);
    }

    private static java.time.Instant timestamp(Timestamp value) {
        return value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
