package com.real.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RequestContext {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestContext.class.getName() + ".requestId";
    public static final String TRACE_ID_ATTRIBUTE = RequestContext.class.getName() + ".traceId";
    public static final String REQUEST_ID_MDC = "requestId";
    public static final String TRACE_ID_MDC = "traceId";

    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$");
    private static final Pattern TRACE_PARENT_PATTERN =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestContext() {
    }

    public static String resolveRequestId(String supplied) {
        if (supplied != null && REQUEST_ID_PATTERN.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    public static String resolveTraceId(String traceParent) {
        if (traceParent != null) {
            Matcher matcher = TRACE_PARENT_PATTERN.matcher(traceParent);
            if (matcher.matches() && !matcher.group(1).equals("00000000000000000000000000000000")) {
                return matcher.group(1);
            }
        }
        byte[] bytes = new byte[16];
        do {
            RANDOM.nextBytes(bytes);
        } while (allZero(bytes));
        return HexFormat.of().formatHex(bytes);
    }

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? resolveRequestId(null) : value.toString();
    }

    public static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TRACE_ID_ATTRIBUTE);
        return value == null ? resolveTraceId(null) : value.toString();
    }

    public static void putMdc(String requestId, String traceId) {
        MDC.put(REQUEST_ID_MDC, requestId);
        MDC.put(TRACE_ID_MDC, traceId);
    }

    public static void clearMdc() {
        MDC.remove(REQUEST_ID_MDC);
        MDC.remove(TRACE_ID_MDC);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
