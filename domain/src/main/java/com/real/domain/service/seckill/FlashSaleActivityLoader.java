package com.real.domain.service.seckill;

import com.real.common.exception.SeckillServiceUnavailableException;
import com.real.domain.entity.FlashSaleActivityFact;
import com.real.domain.mapper.FlashSaleActivityMapper;
import com.real.infrastructure.redis.SeckillRedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FlashSaleActivityLoader {
    private static final Set<String> ACTIVITY_STATUSES =
            Set.of("DRAFT", "SCHEDULED", "ACTIVE", "PAUSED", "ENDED", "CANCELED");
    private static final DefaultRedisScript<List> LOAD_SCRIPT = script();

    private final FlashSaleActivityMapper activityMapper;
    private final StringRedisTemplate seckillRedis;
    private final Duration retention;

    public FlashSaleActivityLoader(
            FlashSaleActivityMapper activityMapper,
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate seckillRedis,
            @Value("${hotshop.seckill.activity-retention:7d}") Duration retention
    ) {
        this.activityMapper = activityMapper;
        this.seckillRedis = seckillRedis;
        this.retention = retention;
    }

    public FlashSaleLoadResult load(long activityId) {
        FlashSaleActivityFact fact = activityMapper.findFactById(activityId);
        if (fact == null) {
            return unavailable(FlashSaleLoadCode.ACTIVITY_NOT_FOUND, activityId, "Activity was not found");
        }
        String validationFailure = validate(fact);
        if (validationFailure != null) {
            return new FlashSaleLoadResult(
                    FlashSaleLoadCode.ACTIVITY_INVALID,
                    activityId,
                    fact.version(),
                    null,
                    fact.availableStock(),
                    null,
                    0,
                    0,
                    0,
                    false,
                    validationFailure
            );
        }

        String loadId = UUID.randomUUID().toString().replace("-", "");
        List<String> keys = List.of(
                SeckillRedisKeys.activityMetadata(activityId),
                SeckillRedisKeys.availableStock(activityId),
                SeckillRedisKeys.reservationStream(activityId),
                SeckillRedisKeys.loadStagingMetadata(activityId, loadId),
                SeckillRedisKeys.loadStagingStock(activityId, loadId)
        );
        long expireAt = fact.endsAt()
                .toInstant(ZoneOffset.UTC)
                .plus(retention)
                .getEpochSecond();
        List<?> raw;
        try {
            raw = seckillRedis.execute(
                    LOAD_SCRIPT,
                    keys,
                    Long.toString(fact.activityId()),
                    Long.toString(fact.productId()),
                    money(fact.salePrice()),
                    Integer.toString(fact.totalStock()),
                    Integer.toString(fact.availableStock()),
                    Integer.toString(fact.perUserLimit()),
                    fact.status(),
                    Long.toString(fact.startsAt().toInstant(ZoneOffset.UTC).toEpochMilli()),
                    Long.toString(fact.endsAt().toInstant(ZoneOffset.UTC).toEpochMilli()),
                    Integer.toString(fact.version()),
                    Long.toString(expireAt)
            );
        } catch (DataAccessException exception) {
            throw new SeckillServiceUnavailableException(exception);
        }
        if (raw == null || raw.size() < 4) {
            throw new IllegalStateException("Activity loader returned an invalid result");
        }

        FlashSaleLoadCode code = FlashSaleLoadCode.valueOf(text(raw.get(0)));
        Integer redisVersion = integerOrNull(raw.get(1));
        Integer redisStock = integerOrNull(raw.get(2));
        long eventCount = longValue(raw.get(3));
        Reconciliation reconciliation = reconcile(fact, redisStock, eventCount);
        return new FlashSaleLoadResult(
                code,
                activityId,
                fact.version(),
                redisVersion,
                fact.availableStock(),
                redisStock,
                eventCount,
                reconciliation.reservationRecords(),
                reconciliation.reservedQuantity(),
                reconciliation.consistent(),
                detail(code)
        );
    }

    private Reconciliation reconcile(
            FlashSaleActivityFact fact,
            Integer redisStock,
            long expectedEventCount
    ) {
        if (redisStock == null) {
            return new Reconciliation(0, 0, false);
        }
        List<MapRecord<String, Object, Object>> records = seckillRedis.opsForStream().range(
                SeckillRedisKeys.reservationStream(fact.activityId()),
                Range.unbounded()
        );
        if (records == null) {
            records = List.of();
        }

        long reservationRecords = 0;
        long reservedQuantity = 0;
        boolean referencesConsistent = true;
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> values = record.getValue();
            String reservationNo = value(values, "reservationNo");
            String userId = value(values, "userId");
            String quantity = value(values, "quantity");
            if (reservationNo == null || userId == null || quantity == null) {
                referencesConsistent = false;
                continue;
            }
            try {
                reservedQuantity += Long.parseLong(quantity);
                String reservationKey = SeckillRedisKeys.reservation(fact.activityId(), reservationNo);
                String userKey = SeckillRedisKeys.userReservation(
                        fact.activityId(),
                        Long.parseLong(userId)
                );
                if (Boolean.TRUE.equals(seckillRedis.hasKey(reservationKey))) {
                    reservationRecords++;
                } else {
                    referencesConsistent = false;
                }
                if (!reservationNo.equals(seckillRedis.opsForValue().get(userKey))) {
                    referencesConsistent = false;
                }
            } catch (NumberFormatException exception) {
                referencesConsistent = false;
            }
        }
        long deducted = (long) fact.availableStock() - redisStock;
        boolean consistent = redisStock >= 0
                && redisStock <= fact.availableStock()
                && records.size() == expectedEventCount
                && reservationRecords == expectedEventCount
                && reservedQuantity == deducted
                && referencesConsistent;
        return new Reconciliation(reservationRecords, reservedQuantity, consistent);
    }

    private String validate(FlashSaleActivityFact fact) {
        if (fact.catalogProductId() == null
                || fact.catalogDeletedAt() != null
                || !"ACTIVE".equals(fact.catalogStatus())) {
            return "Catalog Product must exist, be active, and not be deleted";
        }
        if (fact.catalogProductId() != fact.productId()) {
            return "Activity product reference is invalid";
        }
        if (fact.salePrice() == null || fact.catalogPrice() == null
                || fact.salePrice().signum() < 0
                || fact.salePrice().scale() > 2
                || fact.catalogPrice().signum() < 0
                || fact.salePrice().compareTo(fact.catalogPrice()) > 0) {
            return "Activity price is invalid";
        }
        if (fact.totalStock() <= 0
                || fact.availableStock() < 0
                || fact.availableStock() > fact.totalStock()
                || fact.catalogStock() == null
                || fact.totalStock() > fact.catalogStock()) {
            return "Activity inventory is invalid";
        }
        if (fact.perUserLimit() <= 0 || fact.perUserLimit() > fact.totalStock()) {
            return "Per-User limit is invalid";
        }
        if (fact.startsAt() == null || fact.endsAt() == null
                || !fact.endsAt().isAfter(fact.startsAt())) {
            return "Activity time window is invalid";
        }
        if (!ACTIVITY_STATUSES.contains(fact.status())) {
            return "Activity status is invalid";
        }
        if (fact.version() < 0) {
            return "Activity version is invalid";
        }
        return null;
    }

    private FlashSaleLoadResult unavailable(
            FlashSaleLoadCode code,
            long activityId,
            String detail
    ) {
        return new FlashSaleLoadResult(
                code,
                activityId,
                -1,
                null,
                0,
                null,
                0,
                0,
                0,
                false,
                detail
        );
    }

    private static DefaultRedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/load-flash-sale-activity-v1.lua"));
        script.setResultType(List.class);
        return script;
    }

    private String money(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private Integer integerOrNull(Object value) {
        String text = text(value);
        return text.isBlank() ? null : Integer.valueOf(text);
    }

    private long longValue(Object value) {
        String text = text(value);
        return text.isBlank() ? 0 : Long.parseLong(text);
    }

    private String detail(FlashSaleLoadCode code) {
        return switch (code) {
            case LOADED -> "Activity facts loaded into redis-seckill";
            case IDEMPOTENT -> "The same database activity version is already loaded";
            case STALE_VERSION -> "An older database activity version cannot replace Redis facts";
            case RESERVATIONS_EXIST -> "An activity with Reservations cannot be reset by ordinary loading";
            case INTERNAL_STATE_INVALID -> "Redis activity state is invalid";
            case ACTIVITY_NOT_FOUND -> "Activity was not found";
            case ACTIVITY_INVALID -> "Activity facts are invalid";
        };
    }

    private record Reconciliation(
            long reservationRecords,
            long reservedQuantity,
            boolean consistent
    ) {
    }
}
