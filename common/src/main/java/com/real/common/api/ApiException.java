package com.real.common.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, null);
    }

    public ApiException(
            HttpStatus status,
            String code,
            String title,
            String detail,
            Long retryAfterSeconds
    ) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "Invalid request", detail);
    }

    public static ApiException notFound(String resourceName) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Resource not found",
                resourceName + " was not found"
        );
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Conflict", detail);
    }

    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many requests",
                "Request rate limit exceeded",
                retryAfterSeconds
        );
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
