package com.real.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemResponseWriterTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();

    @Test
    void writesProblemMediaTypeCorrelationHeadersAndFlattenedExtensions() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemResponseWriter(mapper).write(request, response, ApiException.rateLimited(15));

        JsonNode json = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("15");
        assertThat(response.getHeader(RequestContext.REQUEST_ID_HEADER)).isEqualTo("request-1");
        assertThat(response.getHeader(RequestContext.TRACE_ID_HEADER)).isEqualTo("trace-1");
        assertThat(json.get("status").intValue()).isEqualTo(429);
        assertThat(json.get("type").stringValue()).isEqualTo("https://hotshop.local/problems/rate-limited");
        assertThat(json.get("instance").stringValue()).isEqualTo("/api/v1/products");
        assertThat(json.get("code").stringValue()).isEqualTo("RATE_LIMITED");
        assertThat(json.get("requestId").stringValue()).isEqualTo("request-1");
        assertThat(json.get("traceId").stringValue()).isEqualTo("trace-1");
        assertThat(json.has("properties")).isFalse();
        assertThat(json.has("violations")).isFalse();
    }

    @Test
    void keepsValidationViolationsAsFlattenedStructuredExtension() {
        ProblemDetail problem = ProblemDetailsFactory.create(request(), HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED", "Invalid request", "Validation failed",
                List.of(new ApiViolation("quantity", "Positive", "must be positive")));

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(problem));

        assertThat(json.at("/violations/0/field").stringValue()).isEqualTo("quantity");
        assertThat(json.at("/violations/0/code").stringValue()).isEqualTo("Positive");
        assertThat(json.has("properties")).isFalse();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "request-1");
        request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, "trace-1");
        return request;
    }
}
