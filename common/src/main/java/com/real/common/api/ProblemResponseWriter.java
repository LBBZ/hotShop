package com.real.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
@Component
public class ProblemResponseWriter {
    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ApiException exception)
            throws IOException {
        ProblemDetail problem = ProblemDetailsFactory.create(
                request,
                exception.getStatus(),
                exception.getCode(),
                exception.getTitle(),
                exception.getMessage(),
                null
        );
        response.setStatus(exception.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(RequestContext.REQUEST_ID_HEADER, RequestContext.requestId(request));
        response.setHeader(RequestContext.TRACE_ID_HEADER, RequestContext.traceId(request));
        if (exception.getRetryAfterSeconds() != null) {
            response.setHeader("Retry-After", exception.getRetryAfterSeconds().toString());
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
