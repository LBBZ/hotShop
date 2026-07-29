package com.real.domain.service.seckill;

import com.real.common.exception.SeckillServiceUnavailableException;
import com.real.infrastructure.redis.SeckillRedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class FlashSaleReservationService {
    private static final DefaultRedisScript<List> RESERVATION_SCRIPT = script();

    private final StringRedisTemplate seckillRedis;
    private final Duration reservationTtl;
    private final Duration idempotencyTtl;

    public FlashSaleReservationService(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate seckillRedis,
            @Value("${hotshop.seckill.reservation-ttl:7d}") Duration reservationTtl,
            @Value("${hotshop.seckill.idempotency-ttl:24h}") Duration idempotencyTtl
    ) {
        this.seckillRedis = seckillRedis;
        this.reservationTtl = reservationTtl;
        this.idempotencyTtl = idempotencyTtl;
    }

    public FlashSaleReservationResult reserve(
            long activityId,
            long userId,
            int quantity,
            String idempotencyKey,
            String requestId
    ) {
        String keyHash = sha256(idempotencyKey);
        String fingerprint = sha256("v1\n" + activityId + "\n" + quantity);
        String reservationNo = "rsv_" + compactUuid();
        String eventId = "evt_" + compactUuid();
        List<String> keys = List.of(
                SeckillRedisKeys.activityMetadata(activityId),
                SeckillRedisKeys.availableStock(activityId),
                SeckillRedisKeys.userReservation(activityId, userId),
                SeckillRedisKeys.idempotency(userId, keyHash),
                SeckillRedisKeys.reservation(activityId, reservationNo),
                SeckillRedisKeys.reservationStream(activityId)
        );
        List<?> raw;
        try {
            raw = seckillRedis.execute(
                    RESERVATION_SCRIPT,
                    keys,
                    Long.toString(activityId),
                    Long.toString(userId),
                    Integer.toString(quantity),
                    fingerprint,
                    reservationNo,
                    eventId,
                    requestId,
                    Long.toString(reservationTtl.toSeconds()),
                    Long.toString(idempotencyTtl.toSeconds()),
                    keyHash
            );
        } catch (DataAccessException exception) {
            throw new SeckillServiceUnavailableException(exception);
        }
        if (raw == null || raw.size() < 4) {
            throw new IllegalStateException("Reservation script returned an invalid result");
        }
        FlashSaleReservationCode code = FlashSaleReservationCode.valueOf(text(raw.get(0)));
        return new FlashSaleReservationResult(
                code,
                blankToNull(text(raw.get(1))),
                activityId,
                code == FlashSaleReservationCode.ACCEPTED
                        || code == FlashSaleReservationCode.IDEMPOTENT_REPLAY
                        ? "RESERVED"
                        : null,
                blankToNull(text(raw.get(2))),
                blankToNull(text(raw.get(3)))
        );
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static DefaultRedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/flash-sale-reservation-v1.lua"));
        script.setResultType(List.class);
        return script;
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
