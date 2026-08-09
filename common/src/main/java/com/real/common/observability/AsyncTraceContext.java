package com.real.common.observability;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** W3C carrier helpers for persisted events and broker headers. */
public final class AsyncTraceContext {
    public static final String TRACE_PARENT = "traceparent";
    public static final String TRACE_STATE = "tracestate";
    public static final String REQUEST_ID = "x-request-id";
    private static final Pattern TRACE_PARENT_PATTERN = Pattern.compile(
            "^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$"
    );
    private static final Pattern TRACE_STATE_PATTERN = Pattern.compile("^[\\x20-\\x7e]{1,512}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AsyncTraceContext() {
    }

    public static String currentTraceParent() {
        String traceId = MDC.get("traceId");
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            return "";
        }
        String spanId = MDC.get("spanId");
        if (spanId == null || !spanId.matches("[0-9a-f]{16}")) {
            byte[] random = new byte[8];
            do {
                RANDOM.nextBytes(random);
                spanId = HexFormat.of().formatHex(random);
            } while ("0000000000000000".equals(spanId));
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    public static String currentTraceState() {
        return sanitizeTraceState(MDC.get(TRACE_STATE));
    }

    /** Returns a bounded, single-line W3C tracestate carrier or an empty value. */
    public static String sanitizeTraceState(String value) {
        if (value == null || !TRACE_STATE_PATTERN.matcher(value).matches()
                || value.startsWith(" ") || value.endsWith(" ")) {
            return "";
        }
        return value;
    }

    public static Parsed parse(String value) {
        if (value == null) {
            return Parsed.invalid();
        }
        Matcher matcher = TRACE_PARENT_PATTERN.matcher(value);
        if (!matcher.matches()
                || "ff".equals(matcher.group(1))
                || "00000000000000000000000000000000".equals(matcher.group(2))
                || "0000000000000000".equals(matcher.group(3))) {
            return Parsed.invalid();
        }
        return new Parsed(true, matcher.group(2), matcher.group(3), matcher.group(4));
    }

    public record Parsed(boolean valid, String traceId, String parentSpanId, String flags) {
        private static Parsed invalid() {
            return new Parsed(false, "", "", "");
        }
    }
}
