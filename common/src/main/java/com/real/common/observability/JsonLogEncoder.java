package com.real.common.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free JSON encoder with a deliberately narrow, redacted schema. */
public final class JsonLogEncoder extends EncoderBase<ILoggingEvent> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private String service = "unknown";
    private String environment = "local";

    public void setService(String service) {
        this.service = service;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        IThrowableProxy throwable = event.getThrowableProxy();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        json.put("level", event.getLevel().toString());
        json.put("service", safe(service));
        json.put("environment", safe(environment));
        json.put("event", safe(mdc.getOrDefault("event", "application.log")));
        json.put("name", safe(event.getLoggerName()));
        json.put("requestId", safe(mdc.getOrDefault("requestId", "")));
        json.put("traceId", safe(mdc.getOrDefault("traceId", "")));
        json.put("spanId", safe(mdc.getOrDefault("spanId", "")));
        json.put("outcome", safe(mdc.getOrDefault("outcome", "unknown")));
        json.put("errorType", safe(mdc.getOrDefault(
                "errorType", throwable == null ? "" : throwable.getClassName()
        )));
        json.put("message", SensitiveDataSanitizer.sanitize(event.getFormattedMessage()));
        try {
            return (JSON.writeValueAsString(json) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            return ("{\"timestamp\":\"" + Instant.now()
                    + "\",\"level\":\"ERROR\",\"service\":\"logging\","
                    + "\"environment\":\"" + safe(environment)
                    + "\",\"event\":\"logging.encode.failure\",\"name\":\"logger\","
                    + "\"requestId\":\"\",\"traceId\":\"\",\"spanId\":\"\","
                    + "\"outcome\":\"failure\",\"errorType\":\"JsonProcessingException\"}"
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    private static String safe(String value) {
        return SensitiveDataSanitizer.sanitize(value == null ? "" : value);
    }
}
