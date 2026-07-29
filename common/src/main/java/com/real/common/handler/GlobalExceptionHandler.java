package com.real.common.handler;

import com.real.common.api.ApiException;
import com.real.common.api.ApiViolation;
import com.real.common.api.ProblemDetailsFactory;
import com.real.common.api.RequestContext;
import com.real.common.exception.InventoryShortageException;
import com.real.common.exception.SeckillServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(
            ApiException exception,
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.getRetryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, exception.getRetryAfterSeconds().toString());
        }
        return response(
                request,
                exception.getStatus(),
                exception.getCode(),
                exception.getTitle(),
                exception.getMessage(),
                null,
                headers
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new ApiViolation(
                        error.getField(),
                        error.getCode() == null ? "Invalid" : error.getCode(),
                        error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()
                ))
                .toList();
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Validation failed",
                "One or more request fields are invalid",
                violations,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiViolation(
                        violation.getPropertyPath().toString(),
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage()
                ))
                .sorted(Comparator.comparing(ApiViolation::field))
                .toList();
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Validation failed",
                "One or more request parameters are invalid",
                violations,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        ApiViolation violation = new ApiViolation(
                exception.getName(),
                "TypeMismatch",
                "has an invalid format"
        );
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "PARAMETER_INVALID",
                "Invalid parameter",
                "A request parameter has an invalid format",
                List.of(violation),
                new HttpHeaders()
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        ApiViolation violation = new ApiViolation(
                exception.getParameterName(),
                "Missing",
                "is required"
        );
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "PARAMETER_MISSING",
                "Missing parameter",
                "A required request parameter is missing",
                List.of(violation),
                new HttpHeaders()
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        ApiViolation violation = new ApiViolation(
                exception.getHeaderName(),
                "Missing",
                "is required"
        );
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "PARAMETER_MISSING",
                "Missing parameter",
                "A required request header is missing",
                List.of(violation),
                new HttpHeaders()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "MALFORMED_JSON",
                "Malformed JSON",
                "The request body is not valid JSON or contains an invalid value",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        HttpHeaders headers = new HttpHeaders();
        if (!exception.getSupportedHttpMethods().isEmpty()) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return response(
                request,
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Method not allowed",
                "The request method is not supported for this resource",
                null,
                headers
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type",
                "The request media type is not supported for this resource",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE",
                "Not acceptable",
                "The requested response media type is not available for this resource",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Resource not found",
                "The requested resource was not found",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUsernameNotFound(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Resource not found",
                "User was not found",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(InventoryShortageException.class)
    public ResponseEntity<ProblemDetail> handleInventoryShortage(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.CONFLICT,
                "INVENTORY_CONFLICT",
                "Conflict",
                "Inventory is insufficient or changed concurrently",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataConflict(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "Conflict",
                "The request conflicts with the current resource state",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(SeckillServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleSeckillUnavailable(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "SECKILL_SERVICE_UNAVAILABLE",
                "Service unavailable",
                "The flash-sale reservation service is temporarily unavailable",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication required",
                "Valid authentication credentials are required",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Access denied",
                "The authenticated identity is not allowed to perform this operation",
                null,
                new HttpHeaders()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnknown(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Unhandled request failure requestId={} traceId={}",
                RequestContext.requestId(request),
                RequestContext.traceId(request)
        );
        return response(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal server error",
                "An unexpected error occurred",
                null,
                new HttpHeaders()
        );
    }

    private ResponseEntity<ProblemDetail> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title,
            String detail,
            List<ApiViolation> violations,
            HttpHeaders headers
    ) {
        ProblemDetail problem = ProblemDetailsFactory.create(
                request,
                status,
                code,
                title,
                detail,
                violations
        );
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(RequestContext.REQUEST_ID_HEADER, RequestContext.requestId(request));
        headers.set(RequestContext.TRACE_ID_HEADER, RequestContext.traceId(request));
        return new ResponseEntity<>(problem, headers, status);
    }
}
