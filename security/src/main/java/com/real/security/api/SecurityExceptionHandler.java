package com.real.security.api;

import com.real.common.api.ProblemDetailsFactory;
import com.real.common.api.RequestContext;
import com.real.security.service.RefreshCookieService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionHandler {
    private final RefreshCookieService cookieService;

    public SecurityExceptionHandler(RefreshCookieService cookieService) {
        this.cookieService = cookieService;
    }

    @ExceptionHandler(RefreshSessionRejectedException.class)
    public ResponseEntity<ProblemDetail> handleRefreshRejected(
            RefreshSessionRejectedException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetailsFactory.create(
                request,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication required",
                "Valid authentication credentials are required",
                null
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(RequestContext.REQUEST_ID_HEADER, RequestContext.requestId(request));
        headers.set(RequestContext.TRACE_ID_HEADER, RequestContext.traceId(request));
        cookieService.clearCookies(exception.getSessionType())
                .forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie.toString()));
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return new ResponseEntity<>(problem, headers, HttpStatus.UNAUTHORIZED);
    }
}
