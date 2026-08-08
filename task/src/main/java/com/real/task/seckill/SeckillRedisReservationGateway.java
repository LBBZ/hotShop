package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Component
public class SeckillRedisReservationGateway {
    private static final DefaultRedisScript<List> FINALIZE_SCRIPT =
            script("redis/finalize-reservation-order-created-v1.lua");
    private static final DefaultRedisScript<List> COMPENSATE_SCRIPT =
            script("redis/compensate-reservation-v1.lua");

    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public SeckillRedisReservationGateway(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis
    ) {
        this(redis, Clock.systemUTC());
    }

    SeckillRedisReservationGateway(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public ReservationProof verify(ReservationAcceptedEvent event) {
        String key = SeckillRedisKeys.reservation(event.activityId(), event.reservationNo());
        Map<Object, Object> values = redis.opsForHash().entries(key);
        if (values == null || values.isEmpty()) {
            return ReservationProof.invalid("RESERVATION_HASH_MISSING");
        }
        return matches(values, "schemaVersion", "1")
                && matches(values, "reservationNo", event.reservationNo())
                && matches(values, "activityId", Long.toString(event.activityId()))
                && matches(values, "userId", Long.toString(event.userId()))
                && matches(values, "productId", Long.toString(event.productId()))
                && matches(values, "quantity", Integer.toString(event.quantity()))
                && matches(values, "unitPrice", event.unitPrice().toPlainString())
                && matches(values, "currency", event.currency())
                && matches(values, "activityVersion", Integer.toString(event.activityVersion()))
                && matches(values, "idempotencyKeyHash", event.idempotencyKeyHash())
                && matches(values, "requestFingerprint", event.requestFingerprint())
                ? ReservationProof.valid(text(values.get("status")))
                : ReservationProof.invalid("RESERVATION_FACT_CONFLICT");
    }

    public FinalizeResult finalizeOrder(ReservationAcceptedEvent event, String orderId) {
        List<?> raw = redis.execute(
                FINALIZE_SCRIPT,
                List.of(SeckillRedisKeys.reservation(event.activityId(), event.reservationNo())),
                event.reservationNo(),
                Long.toString(event.activityId()),
                Long.toString(event.userId()),
                Long.toString(event.productId()),
                Integer.toString(event.quantity()),
                event.unitPrice().toPlainString(),
                event.requestFingerprint(),
                orderId,
                Long.toString(clock.millis())
        );
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Redis finalize script returned no result");
        }
        return new FinalizeResult(FinalizeCode.valueOf(text(raw.get(0))));
    }

    public CompensationResult compensate(
            ReservationAcceptedEvent event,
            String compensationId,
            String reasonCode
    ) {
        List<?> raw = redis.execute(
                COMPENSATE_SCRIPT,
                List.of(
                        SeckillRedisKeys.reservation(event.activityId(), event.reservationNo()),
                        SeckillRedisKeys.availableStock(event.activityId()),
                        SeckillRedisKeys.userReservation(event.activityId(), event.userId())
                ),
                event.reservationNo(),
                Long.toString(event.activityId()),
                Long.toString(event.userId()),
                Integer.toString(event.quantity()),
                compensationId,
                reasonCode,
                Long.toString(clock.millis())
        );
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Redis compensation script returned no result");
        }
        String stock = raw.size() > 1 ? text(raw.get(1)) : "";
        return new CompensationResult(
                CompensationCode.valueOf(text(raw.get(0))),
                stock.isBlank() ? null : Long.valueOf(stock)
        );
    }

    private static boolean matches(Map<Object, Object> values, String key, String expected) {
        return expected.equals(text(values.get(key)));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static DefaultRedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    public record ReservationProof(boolean valid, String status, String reasonCode) {
        static ReservationProof valid(String status) {
            return new ReservationProof(true, status, null);
        }

        static ReservationProof invalid(String reasonCode) {
            return new ReservationProof(false, null, reasonCode);
        }
    }

    public record FinalizeResult(FinalizeCode code) {
        public boolean successful() {
            return code == FinalizeCode.FINALIZED || code == FinalizeCode.IDEMPOTENT;
        }
    }

    public enum FinalizeCode {
        FINALIZED,
        IDEMPOTENT,
        MISSING,
        INVALID_TYPE,
        FACT_CONFLICT,
        ORDER_CONFLICT,
        COMPENSATION_CONFLICT,
        STATUS_CONFLICT
    }

    public record CompensationResult(CompensationCode code, Long resultingStock) {
        public boolean successful() {
            return code == CompensationCode.COMPENSATED || code == CompensationCode.IDEMPOTENT;
        }
    }

    public enum CompensationCode {
        COMPENSATED,
        IDEMPOTENT,
        INVALID_TYPE,
        FACT_CONFLICT,
        COMPENSATION_CONFLICT,
        ORDER_CONFLICT,
        STATUS_CONFLICT,
        USER_SLOT_CONFLICT,
        STOCK_INVALID,
        STORAGE_ERROR
    }
}
