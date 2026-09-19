package com.demo.notification.api.error;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.exception.ResourceNotFoundException;
import com.demo.notification.observability.NotificationMetrics;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Centralized enterprise exception handler strictly implementing RFC 7807 / RFC 9457 Problem Details.
 * <p>
 * Translates domain, validation, rate-limiting, and runtime exceptions into standard {@link ProblemDetail}
 * representations formatted as {@code application/problem+json}, complete with tracing correlation IDs.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final NotificationMetrics notificationMetrics;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex,
                                                                  HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.toList());

        log.warn("Validation failure on URI='{}': {}", request.getRequestURI(), details);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for input payload");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://api.demo.com/errors/validation-error"));
        problem.setProperty("invalidParams", details);
        return buildResponse(problem, HttpStatus.BAD_REQUEST, details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                  HttpServletRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.toList());

        log.warn("Constraint violation on URI='{}': {}", request.getRequestURI(), details);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Constraint violation");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://api.demo.com/errors/constraint-violation"));
        problem.setProperty("invalidParams", details);
        return buildResponse(problem, HttpStatus.BAD_REQUEST, details, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadableException(HttpMessageNotReadableException ex,
                                                                   HttpServletRequest request) {
        String cause = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        log.warn("Malformed HTTP request body on URI='{}': {}", request.getRequestURI(), cause);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request or invalid enum value");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://api.demo.com/errors/malformed-json"));
        return buildResponse(problem, HttpStatus.BAD_REQUEST, List.of(cause), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatchException(MethodArgumentTypeMismatchException ex,
                                                                     HttpServletRequest request) {
        String detail = String.format("Parameter '%s' value '%s' could not be converted to type '%s'",
                ex.getName(), ex.getValue(), ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
        log.warn("Parameter type mismatch on URI='{}': {}", request.getRequestURI(), detail);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid parameter format");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://api.demo.com/errors/type-mismatch"));
        return buildResponse(problem, HttpStatus.BAD_REQUEST, List.of(detail), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFoundException(ResourceNotFoundException ex,
                                                                 HttpServletRequest request) {
        log.warn("Resource not found on URI='{}': {}", request.getRequestURI(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        problem.setType(URI.create("https://api.demo.com/errors/not-found"));
        return buildResponse(problem, HttpStatus.NOT_FOUND, List.of(ex.getMessage()), request);
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RequestNotPermitted ex,
                                                                 HttpServletRequest request) {
        log.warn("Rate limit exceeded for URI='{}': {}", request.getRequestURI(), ex.getMessage());
        notificationMetrics.incrementRateLimited();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Ingestion request throttled to protect downstream systems.");
        problem.setTitle("Too Many Requests");
        problem.setType(URI.create("https://api.demo.com/errors/rate-limit-exceeded"));
        return buildResponse(problem, HttpStatus.TOO_MANY_REQUESTS, List.of("Rate limit exceeded"), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        log.warn("HTTP method '{}' not supported for URI='{}'", ex.getMethod(), request.getRequestURI());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        problem.setTitle("Method Not Allowed");
        problem.setType(URI.create("https://api.demo.com/errors/method-not-allowed"));
        return buildResponse(problem, HttpStatus.METHOD_NOT_ALLOWED,
                List.of("Supported methods: " + (ex.getSupportedHttpMethods() != null ? ex.getSupportedHttpMethods() : "none")),
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                     HttpServletRequest request) {
        log.warn("Content-Type '{}' not supported for URI='{}'", ex.getContentType(), request.getRequestURI());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
        problem.setTitle("Unsupported Media Type");
        problem.setType(URI.create("https://api.demo.com/errors/unsupported-media-type"));
        return buildResponse(problem, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                List.of("Supported media types: " + ex.getSupportedMediaTypes()), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request URI='{}'", request.getRequestURI(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Please reference the correlationId when contacting support.");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://api.demo.com/errors/internal-error"));
        return buildResponse(problem, HttpStatus.INTERNAL_SERVER_ERROR, List.of("Internal server fault"), request);
    }

    private ResponseEntity<ProblemDetail> buildResponse(ProblemDetail problem,
                                                        HttpStatus status,
                                                        List<String> details,
                                                        HttpServletRequest request) {
        String correlationId = resolveCorrelationId();
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", correlationId);
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("error", status.getReasonPhrase());
        problem.setProperty("message", problem.getDetail());
        problem.setProperty("details", details);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId)
                .body(problem);
    }

    private String resolveCorrelationId() {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }

    private String formatFieldError(FieldError fieldError) {
        return String.format("Field '%s': %s (rejected value: '%s')",
                fieldError.getField(), fieldError.getDefaultMessage(), fieldError.getRejectedValue());
    }
}
