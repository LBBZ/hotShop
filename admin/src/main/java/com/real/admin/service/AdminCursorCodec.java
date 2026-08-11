package com.real.admin.service;

import com.real.common.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

@Component
public final class AdminCursorCodec {
    private static final String VERSION = "a1";
    private static final int MAX_LENGTH = 768;
    private final byte[] key;

    public AdminCursorCodec(@Value("${hotshop.admin.operations.cursor-secret:}") String configured) {
        if (configured != null && configured.getBytes(StandardCharsets.UTF_8).length >= 32) {
            this.key = configured.getBytes(StandardCharsets.UTF_8);
        } else {
            this.key = new byte[32];
            new SecureRandom().nextBytes(this.key);
        }
    }

    public String encodeLong(String scope, long id) {
        return encode(VERSION + "|" + scope + "|L|" + id);
    }

    public LongCursor decodeLong(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = verified(cursor).split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])
                || !scope.equals(parts[1]) || !"L".equals(parts[2])) {
            throw invalid();
        }
        try {
            long id = Long.parseLong(parts[3]);
            if (id <= 0) {
                throw invalid();
            }
            return new LongCursor(id);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    public String encodeTimeLong(String scope, LocalDateTime time, long id) {
        return encode(VERSION + "|" + scope + "|T|" + time + "|" + id);
    }

    public String encodeTimeString(String scope, LocalDateTime time, String id) {
        if (id == null || !id.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw invalid();
        }
        return encode(VERSION + "|" + scope + "|S|" + time + "|" + id);
    }

    public TimeStringCursor decodeTimeString(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = verified(cursor).split("\\|", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0])
                || !scope.equals(parts[1]) || !"S".equals(parts[2])) {
            throw invalid();
        }
        try {
            LocalDateTime time = LocalDateTime.parse(parts[3]);
            if (!parts[4].matches("^[A-Za-z0-9_-]{1,64}$")) {
                throw invalid();
            }
            return new TimeStringCursor(time, parts[4]);
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    public TimeLongCursor decodeTimeLong(String cursor, String scope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String[] parts = verified(cursor).split("\\|", -1);
        if (parts.length != 5 || !VERSION.equals(parts[0])
                || !scope.equals(parts[1]) || !"T".equals(parts[2])) {
            throw invalid();
        }
        try {
            LocalDateTime time = LocalDateTime.parse(parts[3]);
            long id = Long.parseLong(parts[4]);
            if (id <= 0) {
                throw invalid();
            }
            return new TimeLongCursor(time, id);
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw invalid();
        }
    }

    private String encode(String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(bytes));
    }

    private String verified(String cursor) {
        if (cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        String[] token = cursor.split("\\.", -1);
        if (token.length != 2) {
            throw invalid();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(token[0]);
            byte[] signature = Base64.getUrlDecoder().decode(token[1]);
            if (!MessageDigest.isEqual(signature, sign(payload))) {
                throw invalid();
            }
            return new String(payload, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Admin cursor signing is unavailable", exception);
        }
    }

    private ApiException invalid() {
        return ApiException.badRequest("CURSOR_INVALID", "The cursor is invalid or belongs to another list");
    }

    public record LongCursor(long id) { }
    public record TimeLongCursor(LocalDateTime time, long id) { }
    public record TimeStringCursor(LocalDateTime time, String id) { }
}
