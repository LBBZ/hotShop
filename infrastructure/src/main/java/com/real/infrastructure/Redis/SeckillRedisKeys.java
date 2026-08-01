package com.real.infrastructure.redis;

import java.nio.charset.StandardCharsets;

public final class SeckillRedisKeys {
    public static final String VERSION = "v1";
    public static final String HASH_TAG = "{hotshop-seckill-v1}";
    private static final String PREFIX = "hotshop:seckill:" + VERSION + ":" + HASH_TAG;

    private SeckillRedisKeys() {
    }

    public static String activityMetadata(long activityId) {
        return activity(activityId) + ":meta";
    }

    public static String availableStock(long activityId) {
        return activity(activityId) + ":stock";
    }

    public static String userReservation(long activityId, long userId) {
        return activity(activityId) + ":user:" + userId + ":reservation";
    }

    public static String idempotency(long userId, String idempotencyKeyHash) {
        return PREFIX + ":idempotency:user:" + userId + ":" + idempotencyKeyHash;
    }

    public static String reservation(long activityId, String reservationNo) {
        return activity(activityId) + ":reservation:" + reservationNo;
    }

    public static String reservationStream(long activityId) {
        return activity(activityId) + ":reservations";
    }

    public static String reservationStreamRegistry() {
        return PREFIX + ":registry:reservation-streams";
    }

    public static String loadStagingMetadata(long activityId, String loadId) {
        return activity(activityId) + ":load:" + loadId + ":meta";
    }

    public static String loadStagingStock(long activityId, String loadId) {
        return activity(activityId) + ":load:" + loadId + ":stock";
    }

    public static int clusterSlot(String key) {
        String hashInput = hashTagValue(key);
        int crc = 0;
        for (byte value : hashInput.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (value & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0
                        ? ((crc << 1) ^ 0x1021) & 0xffff
                        : (crc << 1) & 0xffff;
            }
        }
        return crc & 0x3fff;
    }

    private static String activity(long activityId) {
        if (activityId <= 0) {
            throw new IllegalArgumentException("Activity ID must be positive");
        }
        return PREFIX + ":activity:" + activityId;
    }

    public static Long activityIdFromReservationStream(String streamKey) {
        String prefix = PREFIX + ":activity:";
        String suffix = ":reservations";
        if (streamKey == null || !streamKey.startsWith(prefix) || !streamKey.endsWith(suffix)) {
            return null;
        }
        String raw = streamKey.substring(prefix.length(), streamKey.length() - suffix.length());
        if (!raw.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            long activityId = Long.parseLong(raw);
            return activityId > 0 ? activityId : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String hashTagValue(String key) {
        int start = key.indexOf('{');
        if (start >= 0) {
            int end = key.indexOf('}', start + 1);
            if (end > start + 1) {
                return key.substring(start + 1, end);
            }
        }
        return key;
    }
}
