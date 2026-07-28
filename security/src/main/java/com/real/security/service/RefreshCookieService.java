package com.real.security.service;

import com.real.common.api.ApiException;
import com.real.security.config.SecurityProperties;
import com.real.security.identity.SessionType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

@Service
public class RefreshCookieService {
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String USER_REFRESH_COOKIE = "hotshop_user_refresh";
    public static final String USER_CSRF_COOKIE = "hotshop_user_csrf";
    public static final String ADMIN_REFRESH_COOKIE = "hotshop_admin_refresh";
    public static final String ADMIN_CSRF_COOKIE = "hotshop_admin_csrf";

    private final SecurityProperties properties;

    public RefreshCookieService(SecurityProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie refreshCookie(SessionType type, String value) {
        return cookie(refreshName(type), value, true, Duration.ofSeconds(properties.getRefresh().getTtlSeconds()));
    }

    public ResponseCookie csrfCookie(SessionType type, String value) {
        return cookie(csrfName(type), value, false, Duration.ofSeconds(properties.getRefresh().getTtlSeconds()));
    }

    public List<ResponseCookie> clearCookies(SessionType type) {
        return List.of(
                cookie(refreshName(type), "", true, Duration.ZERO),
                cookie(csrfName(type), "", false, Duration.ZERO)
        );
    }

    public void requireValidCsrf(String csrfCookie, String csrfHeader) {
        if (csrfCookie == null
                || csrfHeader == null
                || !MessageDigest.isEqual(
                        csrfCookie.getBytes(StandardCharsets.UTF_8),
                        csrfHeader.getBytes(StandardCharsets.UTF_8)
                )) {
            throw ApiException.forbidden(
                    "CSRF_TOKEN_INVALID",
                    "A valid CSRF cookie and header are required"
            );
        }
    }

    public String refreshName(SessionType type) {
        return type == SessionType.USER ? USER_REFRESH_COOKIE : ADMIN_REFRESH_COOKIE;
    }

    public String csrfName(SessionType type) {
        return type == SessionType.USER ? USER_CSRF_COOKIE : ADMIN_CSRF_COOKIE;
    }

    public String path(SessionType type) {
        return type == SessionType.USER ? "/api/v1/auth" : "/admin/api/v1/auth";
    }

    private ResponseCookie cookie(
            String name,
            String value,
            boolean httpOnly,
            Duration maxAge
    ) {
        SessionType type = name.startsWith("hotshop_user_") ? SessionType.USER : SessionType.ADMIN;
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(properties.getRefresh().isSecureCookie())
                .sameSite("Strict")
                .path(path(type))
                .maxAge(maxAge)
                .build();
    }
}
