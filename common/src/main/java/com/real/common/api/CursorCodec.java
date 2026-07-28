package com.real.common.api;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

public final class CursorCodec {
    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 512;

    private CursorCodec() {
    }

    public static String encodeLong(String scope, long id) {
        return encode(VERSION + "|" + scope + "|" + id);
    }

    public static LongCursor decodeLong(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = decode(cursor).split("\\|", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || !expectedScope.equals(parts[1])) {
            throw invalidCursor();
        }
        try {
            long id = Long.parseLong(parts[2]);
            if (id <= 0) {
                throw invalidCursor();
            }
            return new LongCursor(id);
        } catch (NumberFormatException exception) {
            throw invalidCursor();
        }
    }

    public static String encodeTimeAndString(String scope, LocalDateTime time, String id) {
        return encode(VERSION + "|" + scope + "|" + time + "|" + id);
    }

    public static TimeStringCursor decodeTimeAndString(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = decodeRequired(cursor, expectedScope);
        try {
            return new TimeStringCursor(LocalDateTime.parse(parts[2]), requireId(parts[3]));
        } catch (DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    public static String encodeTimeAndLong(String scope, LocalDateTime time, long id) {
        return encodeTimeAndString(scope, time, Long.toString(id));
    }

    public static TimeLongCursor decodeTimeAndLong(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        TimeStringCursor decoded = decodeTimeAndString(cursor, expectedScope);
        try {
            long id = Long.parseLong(decoded.id());
            if (id <= 0) {
                throw invalidCursor();
            }
            return new TimeLongCursor(decoded.time(), id);
        } catch (NumberFormatException exception) {
            throw invalidCursor();
        }
    }

    private static String[] decodeRequired(String cursor, String expectedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = decode(cursor).split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !expectedScope.equals(parts[1])) {
            throw invalidCursor();
        }
        return parts;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        if (cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 64 || id.indexOf('|') >= 0) {
            throw invalidCursor();
        }
        return id;
    }

    private static ApiException invalidCursor() {
        return ApiException.badRequest("CURSOR_INVALID", "The cursor is invalid or belongs to another list");
    }

    public record LongCursor(long id) {
    }

    public record TimeStringCursor(LocalDateTime time, String id) {
    }

    public record TimeLongCursor(LocalDateTime time, long id) {
    }
}
