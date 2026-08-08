package com.real.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilitySafetyTest {
    @Test
    void redactsNamedSecretsBearerTokensJwtAndBodies() {
        String sentinel = "TASK11_SENTINEL_SECRET";
        String raw = "Authorization: Bearer " + sentinel
                + " password=" + sentinel
                + " apiKey=" + sentinel
                + " requestBody={\"accessToken\":\"" + sentinel + "\"}"
                + " eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature123456";

        String sanitized = SensitiveDataSanitizer.sanitize(raw);

        assertThat(sanitized).doesNotContain(sentinel).doesNotContain("eyJhbGci");
        assertThat(sanitized).contains("[REDACTED]");
    }

    @Test
    void createsAndValidatesW3cCarrierWithoutBusinessIds() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        try {
            String carrier = AsyncTraceContext.currentTraceParent();
            AsyncTraceContext.Parsed parsed = AsyncTraceContext.parse(carrier);
            assertThat(carrier).isEqualTo(
                    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            );
            assertThat(parsed.valid()).isTrue();
            assertThat(parsed.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(AsyncTraceContext.redisKey("request-1"))
                    .matches("hotshop:observability:v1:request:[0-9a-f]{64}");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void rejectsMalformedOrAllZeroTraceParents() {
        assertThat(AsyncTraceContext.parse(
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01"
        ).valid()).isFalse();
        assertThat(AsyncTraceContext.parse("not-a-trace").valid()).isFalse();
    }

    @Test
    void rejectsHighCardinalityBusinessLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(
                new ObservabilityMeterConfiguration().rejectHighCardinalityBusinessTags()
        );

        Counter.builder("hotshop.test.requests")
                .tag("outcome", "success")
                .register(registry)
                .increment();
        Counter.builder("hotshop.test.requests")
                .tag("requestId", "request-123")
                .register(registry)
                .increment();

        assertThat(registry.find("hotshop.test.requests").tag("outcome", "success").counter())
                .isNotNull();
        assertThat(registry.find("hotshop.test.requests").tag("requestId", "request-123").counter())
                .isNull();
    }
}
