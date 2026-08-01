package com.real.domain.service.seckill;

import com.real.common.api.dto.FlashSaleReservationStatusResponse;
import com.real.common.exception.SeckillServiceUnavailableException;
import com.real.infrastructure.redis.SeckillRedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FlashSaleReservationStatusService {
    private static final Set<String> REDIS_STATUSES =
            Set.of("RESERVED", "ORDER_CREATED", "COMPENSATING", "COMPENSATED");

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate seckillRedis;

    public FlashSaleReservationStatusService(
            JdbcTemplate jdbc,
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate seckillRedis
    ) {
        this.jdbc = jdbc;
        this.seckillRedis = seckillRedis;
    }

    public FlashSaleReservationStatusResponse findOwned(
            long activityId,
            String reservationNo,
            long userId
    ) {
        try {
            List<FlashSaleReservationStatusResponse> durable = jdbc.query("""
                    SELECT reservation_no, activity_id, status, order_id,
                           quantity, reserved_amount,
                           COALESCE(currency, 'CNY') AS currency
                      FROM sale_reservation
                     WHERE activity_id = ?
                       AND reservation_no = ?
                       AND user_id = ?
                    """, (resultSet, rowNum) -> new FlashSaleReservationStatusResponse(
                    resultSet.getString("reservation_no"),
                    resultSet.getLong("activity_id"),
                    resultSet.getString("status"),
                    resultSet.getString("order_id"),
                    resultSet.getInt("quantity"),
                    resultSet.getBigDecimal("reserved_amount"),
                    resultSet.getString("currency")
            ), activityId, reservationNo, userId);
            if (durable.size() == 1) {
                return durable.get(0);
            }
            if (!durable.isEmpty()) {
                return null;
            }
        } catch (DataAccessException exception) {
            throw new SeckillServiceUnavailableException(exception);
        }

        try {
            Map<Object, Object> redis = seckillRedis.opsForHash().entries(
                    SeckillRedisKeys.reservation(activityId, reservationNo)
            );
            if (redis == null || redis.isEmpty()
                    || !"1".equals(value(redis, "schemaVersion"))
                    || !reservationNo.equals(value(redis, "reservationNo"))
                    || !Long.toString(activityId).equals(value(redis, "activityId"))
                    || !Long.toString(userId).equals(value(redis, "userId"))
                    || !REDIS_STATUSES.contains(value(redis, "status"))) {
                return null;
            }
            int quantity = Integer.parseInt(value(redis, "quantity"));
            BigDecimal unitPrice = new BigDecimal(value(redis, "unitPrice"));
            return new FlashSaleReservationStatusResponse(
                    reservationNo,
                    activityId,
                    value(redis, "status"),
                    value(redis, "orderId"),
                    quantity,
                    unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2),
                    value(redis, "currency")
            );
        } catch (DataAccessException exception) {
            throw new SeckillServiceUnavailableException(exception);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }
}
