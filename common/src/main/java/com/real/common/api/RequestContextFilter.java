package com.real.common.api;

import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component("apiRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            @Nonnull HttpServletRequest request,
            @Nonnull HttpServletResponse response,
            @Nonnull FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = RequestContext.resolveRequestId(request.getHeader(RequestContext.REQUEST_ID_HEADER));
        String traceId = RequestContext.resolveTraceId(request.getHeader("traceparent"));
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(RequestContext.REQUEST_ID_HEADER, requestId);
        response.setHeader(RequestContext.TRACE_ID_HEADER, traceId);
        RequestContext.putMdc(requestId, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            response.setHeader(RequestContext.REQUEST_ID_HEADER, requestId);
            response.setHeader(RequestContext.TRACE_ID_HEADER, traceId);
            RequestContext.clearMdc();
        }
    }
}
