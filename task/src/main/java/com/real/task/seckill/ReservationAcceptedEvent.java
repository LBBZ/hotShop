package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record ReservationAcceptedEvent(
        String eventId,
        String reservationNo,
        long activityId,
        long userId,
        long productId,
        int quantity,
        BigDecimal unitPrice,
        String currency,
        int activityVersion,
        long occurredAtMs,
        String requestId,
        String traceparent,
        String tracestate,
        String idempotencyKeyHash,
        String requestFingerprint,
        String payloadHash
) {
    private static final Pattern EVENT_ID = Pattern.compile("^evt_[0-9a-f]{32}$");
    private static final Pattern RESERVATION_NO = Pattern.compile("^rsv_[0-9a-f]{32}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern POSITIVE = Pattern.compile("^[1-9][0-9]*$");
    private static final Pattern NON_NEGATIVE = Pattern.compile("^(0|[1-9][0-9]*)$");
    private static final Pattern MONEY = Pattern.compile("^(0|[1-9][0-9]*)\\.[0-9]{2}$");
    private static final Set<String> SCHEMA_FIELDS = Set.of(
            "schemaVersion",
            "eventType",
            "eventId",
            "reservationNo",
            "activityId",
            "userId",
            "productId",
            "quantity",
            "unitPrice",
            "currency",
            "status",
            "requestId",
            "traceparent",
            "tracestate",
            "occurredAtMs",
            "activityVersion",
            "idempotencyKeyHash",
            "requestFingerprint"
    );

    public static ParseResult parse(
            String streamKey,
            String streamEntryId,
            Map<Object, Object> raw
    ) {
        String payloadHash = payloadHash(raw);
        List<String> errors = new ArrayList<>();
        Long streamActivityId = SeckillRedisKeys.activityIdFromReservationStream(streamKey);
        if (streamActivityId == null) {
            errors.add("STREAM_KEY_INVALID");
        }
        if (raw.keySet().stream().map(String::valueOf).anyMatch(key -> !SCHEMA_FIELDS.contains(key))) {
            errors.add("UNKNOWN_FIELD");
        }
        require(raw, "schemaVersion", "1", errors, "SCHEMA_VERSION_INVALID");
        require(raw, "eventType", "RESERVATION_ACCEPTED", errors, "EVENT_TYPE_INVALID");
        require(raw, "status", "RESERVED", errors, "STATUS_INVALID");
        require(raw, "currency", "CNY", errors, "CURRENCY_INVALID");

        String eventId = value(raw, "eventId");
        String reservationNo = value(raw, "reservationNo");
        String activity = value(raw, "activityId");
        String user = value(raw, "userId");
        String product = value(raw, "productId");
        String quantity = value(raw, "quantity");
        String unitPrice = value(raw, "unitPrice");
        String activityVersion = value(raw, "activityVersion");
        String occurredAt = value(raw, "occurredAtMs");
        String requestId = value(raw, "requestId");
        String traceparent = value(raw, "traceparent");
        String tracestate = value(raw, "tracestate");
        String idempotencyHash = value(raw, "idempotencyKeyHash");
        String fingerprint = value(raw, "requestFingerprint");

        match(eventId, EVENT_ID, errors, "EVENT_ID_INVALID");
        match(reservationNo, RESERVATION_NO, errors, "RESERVATION_NO_INVALID");
        match(activity, POSITIVE, errors, "ACTIVITY_ID_INVALID");
        match(user, POSITIVE, errors, "USER_ID_INVALID");
        match(product, POSITIVE, errors, "PRODUCT_ID_INVALID");
        match(quantity, POSITIVE, errors, "QUANTITY_INVALID");
        match(unitPrice, MONEY, errors, "UNIT_PRICE_INVALID");
        match(activityVersion, NON_NEGATIVE, errors, "ACTIVITY_VERSION_INVALID");
        match(occurredAt, POSITIVE, errors, "OCCURRED_AT_INVALID");
        if (requestId == null || !requestId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")) {
            errors.add("REQUEST_ID_INVALID");
        }
        if (traceparent == null || (!traceparent.isBlank()
                && !com.real.common.observability.AsyncTraceContext.parse(traceparent).valid())) {
            errors.add("TRACEPARENT_INVALID");
        }
        if (tracestate == null || (!tracestate.isBlank()
                && com.real.common.observability.AsyncTraceContext
                        .sanitizeTraceState(tracestate).isBlank())) {
            errors.add("TRACESTATE_INVALID");
        }
        match(idempotencyHash, HASH, errors, "IDEMPOTENCY_HASH_INVALID");
        match(fingerprint, HASH, errors, "FINGERPRINT_INVALID");

        if (!errors.isEmpty()) {
            return ParseResult.invalid(
                    safeEventId(eventId, streamKey, streamEntryId),
                    payloadHash,
                    errors
            );
        }

        try {
            long parsedActivity = Long.parseLong(activity);
            long parsedUser = Long.parseLong(user);
            long parsedProduct = Long.parseLong(product);
            int parsedQuantity = Integer.parseInt(quantity);
            int parsedVersion = Integer.parseInt(activityVersion);
            long parsedOccurredAt = Long.parseLong(occurredAt);
            BigDecimal parsedPrice = new BigDecimal(unitPrice);
            if (streamActivityId == null || streamActivityId != parsedActivity) {
                return ParseResult.invalid(
                        eventId,
                        payloadHash,
                        List.of("STREAM_ACTIVITY_CONFLICT")
                );
            }
            if (parsedPrice.precision() > 19) {
                return ParseResult.invalid(eventId, payloadHash, List.of("UNIT_PRICE_INVALID"));
            }
            BigDecimal parsedTotal = parsedPrice
                    .multiply(BigDecimal.valueOf(parsedQuantity))
                    .setScale(2);
            if (parsedTotal.precision() > 19) {
                return ParseResult.invalid(eventId, payloadHash, List.of("ORDER_AMOUNT_INVALID"));
            }
            // Reject absurd future timestamps without depending on activity lifecycle state.
            if (parsedOccurredAt > Instant.now().plusSeconds(300).toEpochMilli()) {
                return ParseResult.invalid(eventId, payloadHash, List.of("OCCURRED_AT_INVALID"));
            }
            return ParseResult.valid(new ReservationAcceptedEvent(
                    eventId,
                    reservationNo,
                    parsedActivity,
                    parsedUser,
                    parsedProduct,
                    parsedQuantity,
                    parsedPrice,
                    "CNY",
                    parsedVersion,
                    parsedOccurredAt,
                    requestId,
                    traceparent,
                    tracestate,
                    idempotencyHash,
                    fingerprint,
                    payloadHash
            ));
        } catch (ArithmeticException | NumberFormatException exception) {
            return ParseResult.invalid(
                    safeEventId(eventId, streamKey, streamEntryId),
                    payloadHash,
                    List.of("NUMERIC_FIELD_INVALID")
            );
        }
    }

    public BigDecimal totalAmount() {
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2);
        if (total.precision() > 19) {
            throw new IllegalArgumentException("Order amount exceeds database precision");
        }
        return total;
    }

    public String deterministicOrderId() {
        return "ord_" + deterministicHex("hotshop/order/v1/" + reservationNo);
    }

    public String deterministicOrderCreatedOutboxId() {
        return deterministicUuid("hotshop/outbox/order-created/v1/" + reservationNo);
    }

    public String deterministicCompensationId() {
        return "cmp_" + deterministicHex("hotshop/compensation/v1/" + reservationNo);
    }

    public String deterministicCompensationOutboxId() {
        return deterministicUuid("hotshop/outbox/reservation-compensated/v1/" + reservationNo);
    }

    private static void require(
            Map<Object, Object> raw,
            String field,
            String expected,
            List<String> errors,
            String error
    ) {
        if (!expected.equals(value(raw, field))) {
            errors.add(error);
        }
    }

    private static void match(
            String value,
            Pattern pattern,
            List<String> errors,
            String error
    ) {
        if (value == null || !pattern.matcher(value).matches()) {
            errors.add(error);
        }
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static String payloadHash(Map<Object, Object> values) {
        String canonical = values.entrySet().stream()
                .map(entry -> Map.entry(
                        String.valueOf(entry.getKey()),
                        String.valueOf(entry.getValue())
                ))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey().length() + ":" + entry.getKey()
                        + "=" + entry.getValue().length() + ":" + entry.getValue())
                .reduce("", (left, right) -> left + right + "\n");
        return sha256(canonical);
    }

    private static String safeEventId(String raw, String streamKey, String streamEntryId) {
        if (raw != null && raw.length() <= 64 && raw.matches("^[A-Za-z0-9_-]+$")) {
            return raw;
        }
        return "invalid_" + sha256(streamKey + "\n" + streamEntryId).substring(0, 32);
    }

    private static String deterministicHex(String value) {
        return sha256(value).substring(0, 32);
    }

    private static String deterministicUuid(String value) {
        String hex = sha256(value).substring(0, 32);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                + hex.substring(20);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ParseResult(
            ReservationAcceptedEvent event,
            String safeEventId,
            String payloadHash,
            List<String> errors
    ) {
        static ParseResult valid(ReservationAcceptedEvent event) {
            return new ParseResult(event, event.eventId(), event.payloadHash(), List.of());
        }

        static ParseResult invalid(String eventId, String payloadHash, List<String> errors) {
            return new ParseResult(null, eventId, payloadHash, List.copyOf(errors));
        }

        public boolean valid() {
            return event != null;
        }
    }
}
