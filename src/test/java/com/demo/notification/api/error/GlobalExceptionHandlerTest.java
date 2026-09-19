package com.demo.notification.api.error;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.exception.NotificationNotFoundException;
import com.demo.notification.exception.ResourceNotFoundException;
import com.demo.notification.observability.NotificationMetrics;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private NotificationMetrics notificationMetrics;
    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        notificationMetrics = mock(NotificationMetrics.class);
        handler = new GlobalExceptionHandler(notificationMetrics);
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/notifications");
        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, "test-correlation-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Handle MethodArgumentNotValidException: returns 400 ProblemDetail with field errors and correlationId")
    void testHandleValidationException() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("notificationRequestDto", "sourceSystem", "invalid-system",
                false, null, null, "Source system identifier is required");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                mock(MethodParameter.class), bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getTitle()).isEqualTo("Bad Request");
        assertThat(response.getBody().getProperties().get("correlationId")).isEqualTo("test-correlation-id");
        assertThat((List<?>) response.getBody().getProperties().get("details")).isNotEmpty();
    }

    @Test
    @DisplayName("Handle ConstraintViolationException: returns 400 ProblemDetail with violations")
    void testHandleConstraintViolation() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("recipients[0].destination");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ProblemDetail> response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("Handle HttpMessageNotReadableException: returns 400 ProblemDetail with cause")
    void testHandleNotReadableException() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error: Unrecognized token",
                new RuntimeException("Syntax error at line 1"));

        ResponseEntity<ProblemDetail> response = handler.handleNotReadableException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("Handle MethodArgumentTypeMismatchException: returns 400 ProblemDetail")
    void testHandleTypeMismatchException() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "invalid-uuid", UUID.class, "id", mock(MethodParameter.class), null);

        ResponseEntity<ProblemDetail> response = handler.handleTypeMismatchException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getProperties().get("details").toString()).contains("invalid-uuid");
    }

    @Test
    @DisplayName("Handle ResourceNotFoundException: returns 404 ProblemDetail")
    void testHandleNotFoundException() {
        UUID id = UUID.randomUUID();
        ResourceNotFoundException ex = new NotificationNotFoundException(id);

        ResponseEntity<ProblemDetail> response = handler.handleNotFoundException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getDetail()).contains(id.toString());
    }

    @Test
    @DisplayName("Handle RequestNotPermitted: returns 429 ProblemDetail and increments metric")
    void testHandleRateLimitExceeded() {
        RequestNotPermitted ex = mock(RequestNotPermitted.class);
        when(ex.getMessage()).thenReturn("Rate limit exceeded for client");

        ResponseEntity<ProblemDetail> response = handler.handleRateLimitExceeded(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(429);
        assertThat(response.getBody().getTitle()).isEqualTo("Too Many Requests");
        verify(notificationMetrics).incrementRateLimited();
    }

    @Test
    @DisplayName("Handle HttpRequestMethodNotSupportedException: returns 405 ProblemDetail")
    void testHandleMethodNotSupported() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException(
                "PATCH", List.of("GET", "POST"));

        ResponseEntity<ProblemDetail> response = handler.handleMethodNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(405);
    }

    @Test
    @DisplayName("Handle HttpMediaTypeNotSupportedException: returns 415 ProblemDetail")
    void testHandleMediaTypeNotSupported() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ProblemDetail> response = handler.handleMediaTypeNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(415);
    }

    @Test
    @DisplayName("Handle generic Exception: returns 500 ProblemDetail with generic message")
    void testHandleGenericException() {
        Exception ex = new NullPointerException("Database connection lost");

        ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(500);
        assertThat(response.getBody().getTitle()).isEqualTo("Internal Server Error");
    }

    @Test
    @DisplayName("CorrelationId resolution: falls back to generated UUID when MDC is empty")
    void testFallbackCorrelationId() {
        MDC.clear();
        Exception ex = new RuntimeException("Unexpected error");

        ResponseEntity<ProblemDetail> response = handler.handleGenericException(ex, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("correlationId")).isNotNull();
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNotNull();
    }
}
