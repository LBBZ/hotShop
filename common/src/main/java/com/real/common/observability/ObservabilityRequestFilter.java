package com.real.common.observability;

import com.real.common.api.RequestContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Creates a real W3C server span and emits one bounded completion log per request. */
@Component("observabilityRequestFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ObservabilityRequestFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ObservabilityRequestFilter.class);
    private final ObjectProvider<Tracer> tracerProvider;

    public ObservabilityRequestFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    protected void doFilterInternal(@Nonnull HttpServletRequest request,
            @Nonnull HttpServletResponse response, @Nonnull FilterChain chain)
            throws ServletException, IOException {
        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = startSpan(tracer, request);
        String requestId = RequestContext.requestId(request);
        String traceId = span == null ? RequestContext.traceId(request) : span.context().traceId();
        String spanId = span == null ? "" : span.context().spanId();
        request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(RequestContext.TRACE_ID_HEADER, traceId);
        putMdc(requestId, traceId, spanId);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            if (span != null) span.error(failure);
            throw failure;
        } finally {
            putMdc(requestId, traceId, spanId);
            MDC.put("event", "http.request.completed");
            MDC.put("outcome", response.getStatus() < 500 ? "success" : "failure");
            log.info("HTTP request completed method={} status={}",
                    request.getMethod(), response.getStatus());
            MDC.remove("event");
            MDC.remove("outcome");
            MDC.remove("spanId");
            if (span != null) span.end();
        }
    }

    private Span startSpan(Tracer tracer, HttpServletRequest request) {
        if (tracer == null) return null;
        AsyncTraceContext.Parsed parsed = AsyncTraceContext.parse(
                request.getHeader(AsyncTraceContext.TRACE_PARENT)
        );
        TraceContext remote = parsed.valid()
                ? tracer.traceContextBuilder().traceId(parsed.traceId())
                        .spanId(parsed.parentSpanId())
                        .sampled((Integer.parseInt(parsed.flags(), 16) & 1) == 1)
                        .build()
                : null;
        return (remote == null ? tracer.spanBuilder().setNoParent()
                : tracer.spanBuilder().setParent(remote))
                .name("http.request.context")
                .kind(Span.Kind.SERVER)
                .tag("http.request.method", request.getMethod())
                .start();
    }

    private static void putMdc(String requestId, String traceId, String spanId) {
        RequestContext.putMdc(requestId, traceId);
        if (!spanId.isBlank()) MDC.put("spanId", spanId);
    }
}
