package com.real.common.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.real.common.api.RequestContext;
import com.real.common.observability.JsonLogEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerLoggingTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"prior.error.Type", ""})
    void unknownFailureLogsOnlyItsTypeAndRestoresMdc(String previousErrorType) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level originalLevel = logger.getLevel();
        boolean originalAdditive = logger.isAdditive();
        Map<String, String> originalMdc = MDC.getCopyOfContextMap();
        JsonCaptureAppender appender = new JsonCaptureAppender();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ERROR);
        logger.setAdditive(false);
        try {
            MDC.clear();
            MDC.put("requestId", "request-unknown");
            MDC.put("traceId", "trace-unknown");
            MDC.put("outcome", "failure");
            if (previousErrorType != null) {
                MDC.put("errorType", previousErrorType);
            }
            Map<String, String> expectedMdc = MDC.getCopyOfContextMap();
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/audit-logs");
            request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "request-unknown");
            request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, "trace-unknown");
            request.addHeader("Authorization", "Bearer HEADER_SENTINEL");
            request.setQueryString("accessToken=QUERY_SENTINEL");
            request.setContent("{\"password\":\"BODY_SENTINEL\"}".getBytes(StandardCharsets.UTF_8));
            Exception failure = new IllegalStateException("EXCEPTION_MESSAGE_SENTINEL",
                    new RuntimeException("CAUSE_MESSAGE_SENTINEL"));

            var response = new GlobalExceptionHandler().handleUnknown(failure, request);

            assertThat(appender.encoded).hasSize(1);
            assertThat(appender.events).hasSize(1);
            ILoggingEvent event = appender.events.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNull();
            JsonNode log = mapper.readTree(appender.encoded.getFirst());
            assertThat(log.get("errorType").stringValue()).isEqualTo(IllegalStateException.class.getName());
            assertThat(log.get("requestId").stringValue()).isEqualTo("request-unknown");
            assertThat(log.get("traceId").stringValue()).isEqualTo("trace-unknown");
            assertThat(log.get("message").stringValue()).isEqualTo(
                    "Unhandled request failure requestId=request-unknown traceId=trace-unknown");
            assertThat(appender.encoded.getFirst()).doesNotContain("EXCEPTION_MESSAGE_SENTINEL",
                    "CAUSE_MESSAGE_SENTINEL", "HEADER_SENTINEL", "QUERY_SENTINEL", "BODY_SENTINEL");
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(expectedMdc);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            String body = mapper.writeValueAsString(response.getBody());
            JsonNode problem = mapper.readTree(body);
            assertThat(problem.get("status").intValue()).isEqualTo(500);
            assertThat(problem.get("code").stringValue()).isEqualTo("INTERNAL_ERROR");
            assertThat(problem.get("detail").stringValue()).isEqualTo("An unexpected error occurred");
            assertThat(problem.get("requestId").stringValue()).isEqualTo("request-unknown");
            assertThat(problem.get("traceId").stringValue()).isEqualTo("trace-unknown");
            assertThat(body).doesNotContain("IllegalStateException", "EXCEPTION_MESSAGE_SENTINEL",
                    "CAUSE_MESSAGE_SENTINEL", "HEADER_SENTINEL", "QUERY_SENTINEL", "BODY_SENTINEL");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(originalLevel);
            logger.setAdditive(originalAdditive);
            if (originalMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(originalMdc);
            }
        }
    }

    private static final class JsonCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final JsonLogEncoder encoder = new JsonLogEncoder();
        private final List<String> encoded = new ArrayList<>();
        private final List<ILoggingEvent> events = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            // Encode while the event's MDC is active, as the production appender does.
            encoded.add(new String(encoder.encode(event), StandardCharsets.UTF_8));
            events.add(event);
        }
    }
}
