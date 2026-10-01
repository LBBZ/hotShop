package com.real.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonLogEncoderTest {
    @Test
    void producesSingleLineJsonWithEscapedTextAndRedactedSecrets() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getTimeStamp()).thenReturn(1790812800000L);
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getLoggerName()).thenReturn("com.real.test.Logger");
        when(event.getFormattedMessage()).thenReturn("line1\n\"quoted\" password=encoder-sentinel");
        when(event.getMDCPropertyMap()).thenReturn(Map.of(
                "event", "test.event", "requestId", "request-1", "traceId", "trace-1",
                "spanId", "span-1", "outcome", "success", "accessToken", "mdc-sentinel"));
        JsonLogEncoder encoder = new JsonLogEncoder();
        encoder.setService("test\"service");
        encoder.setEnvironment("local");

        String encoded = new String(encoder.encode(event), StandardCharsets.UTF_8);
        JsonNode json = JsonMapper.builder().build().readTree(encoded);

        assertThat(encoded.lines().count()).isEqualTo(1);
        assertThat(encoded).endsWith(System.lineSeparator()).doesNotContain("encoder-sentinel", "mdc-sentinel");
        assertThat(json.get("service").stringValue()).isEqualTo("test\"service");
        assertThat(json.get("message").stringValue()).isEqualTo("line1\n\"quoted\" password=[REDACTED]");
        assertThat(json.get("requestId").stringValue()).isEqualTo("request-1");
        assertThat(json.get("traceId").stringValue()).isEqualTo("trace-1");
        assertThat(json.get("event").stringValue()).isEqualTo("test.event");
        assertThat(json.has("accessToken")).isFalse();
    }
}
