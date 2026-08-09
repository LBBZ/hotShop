package com.real.common.observability;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityRequestFilterTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesValidatedTraceStateOnlyDuringRequestAndRestoresPreviousValue() throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        ObservabilityRequestFilter filter = new ObservabilityRequestFilter(
                beans.getBeanProvider(Tracer.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(AsyncTraceContext.TRACE_STATE, "vendor=value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringRequest = new AtomicReference<>();
        MDC.put(AsyncTraceContext.TRACE_STATE, "outer=value");

        filter.doFilter(request, response, (incoming, outgoing) ->
                duringRequest.set(MDC.get(AsyncTraceContext.TRACE_STATE))
        );

        assertThat(duringRequest).hasValue("vendor=value");
        assertThat(MDC.get(AsyncTraceContext.TRACE_STATE)).isEqualTo("outer=value");
    }

    @Test
    void dropsUntrustedTraceStateDuringRequest() throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        ObservabilityRequestFilter filter = new ObservabilityRequestFilter(
                beans.getBeanProvider(Tracer.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(AsyncTraceContext.TRACE_STATE, "vendor=value\r\nforged=true");
        AtomicReference<String> duringRequest = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) ->
                duringRequest.set(MDC.get(AsyncTraceContext.TRACE_STATE))
        );

        assertThat(duringRequest).hasValue(null);
    }
}
