package com.real.common.observability;

import java.util.List;
import java.util.regex.Pattern;

/** Defensive last-mile sanitizer; callers must still avoid logging request/response bodies. */
public final class SensitiveDataSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(authorization|cookie|set-cookie|password|passwd|api[-_ ]?key|"
                    + "access[-_ ]?token|refresh[-_ ]?token|delegation[-_ ]?token|"
                    + "payment[-_ ]?(?:secret|signature))(\\s*[:=]\\s*)([^\\s,;]+)"
    );
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"
    );
    private static final List<Pattern> BODY_MARKERS = List.of(
            Pattern.compile("(?i)(request|response)(?:Body|_body)\\s*[:=]\\s*\\{.*}"),
            Pattern.compile("(?i)(prompt|modelPrompt)\\s*[:=]\\s*.+")
    );

    private SensitiveDataSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String safe = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        safe = NAMED_SECRET.matcher(safe).replaceAll("$1$2" + REDACTED);
        safe = JWT.matcher(safe).replaceAll(REDACTED);
        for (Pattern marker : BODY_MARKERS) {
            safe = marker.matcher(safe).replaceAll(REDACTED);
        }
        return safe.length() <= 4096 ? safe : safe.substring(0, 4096) + "…[TRUNCATED]";
    }
}
