package com.real.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.List;

public final class ProblemDetailsFactory {
    private static final URI TYPE_BASE = URI.create("https://hotshop.local/problems/");

    private ProblemDetailsFactory() {
    }

    public static ProblemDetail create(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title,
            String detail,
            List<ApiViolation> violations
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", RequestContext.requestId(request));
        problem.setProperty("traceId", RequestContext.traceId(request));
        if (violations != null && !violations.isEmpty()) {
            problem.setProperty("violations", List.copyOf(violations));
        }
        return problem;
    }
}
