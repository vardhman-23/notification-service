package com.demo.notification.api.error;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.exception.NotificationNotFoundException;
import com.demo.notification.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/notifications");
        MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, "test-correlation-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Handle MethodArgumentNotValidException: returns 400 with field errors and correlationId")
    void testHandleValidationException() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("notificationRequestDto", "sourceSystem", "invalid-system",
                false, null, null, "Source system identifier is required");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                mock(MethodParameter.class), bindingResult);

        ResponseEntity<ApiErrorResponse> response = handler.handleValidationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCorrelationId()).isEqualTo("test-correlation-id");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/notifications");
        assertThat(response.getBody().getDetails()).hasSize(1);
        assertThat(response.getBody().getDetails().get(0)).contains("sourceSystem");
    }

    @Test
    @DisplayName("Handle ConstraintViolationException: returns 400 with violation details")
    void testHandleConstraintViolation() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("idempotencyKey");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ApiErrorResponse> response = handler.handleConstraintViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetails().get(0)).contains("idempotencyKey");
    }

    @Test
    @DisplayName("Handle HttpMessageNotReadableException: returns 400 with cause message")
    void testHandleNotReadableException() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Cannot deserialize value", new IllegalArgumentException("Unknown enum value"), null);

        ResponseEntity<ApiErrorResponse> response = handler.handleNotReadableException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed JSON request or invalid enum value");
        assertThat(response.getBody().getDetails()).isNotEmpty();
    }

    @Test
    @DisplayName("Handle MethodArgumentTypeMismatchException: returns 400 with conversion details")
    void testHandleTypeMismatchException() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "invalid-uuid", UUID.class, "id", mock(MethodParameter.class), null);

        ResponseEntity<ApiErrorResponse> response = handler.handleTypeMismatchException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid parameter format");
        assertThat(response.getBody().getDetails().get(0)).contains("Parameter 'id'");
    }

    @Test
    @DisplayName("Handle ResourceNotFoundException: returns 404")
    void testHandleNotFoundException() {
        ResourceNotFoundException ex = new NotificationNotFoundException(UUID.randomUUID());

        ResponseEntity<ApiErrorResponse> response = handler.handleNotFoundException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("Notification not found");
    }

    @Test
    @DisplayName("Handle HttpRequestMethodNotSupportedException: returns 405 Method Not Allowed")
    void testHandleMethodNotSupported() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException(
                "DELETE", List.of("GET", "POST"));

        ResponseEntity<ApiErrorResponse> response = handler.handleMethodNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetails().get(0)).contains("Supported methods");
    }

    @Test
    @DisplayName("Handle HttpMediaTypeNotSupportedException: returns 415 Unsupported Media Type")
    void testHandleMediaTypeNotSupported() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ApiErrorResponse> response = handler.handleMediaTypeNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetails().get(0)).contains("Supported media types");
    }

    @Test
    @DisplayName("Handle Generic Exception: returns 500 without leaking stack traces")
    void testHandleGenericException() {
        RuntimeException ex = new RuntimeException("Simulated unexpected database failure");

        ResponseEntity<ApiErrorResponse> response = handler.handleGenericException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred while processing the request");
        assertThat(response.getBody().getDetails().get(0)).isEqualTo("Simulated unexpected database failure");
    }

    @Test
    @DisplayName("Handle Generic Exception with null message: returns 500 with default message")
    void testHandleGenericExceptionNullMessage() {
        NullPointerException ex = new NullPointerException();

        ResponseEntity<ApiErrorResponse> response = handler.handleGenericException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetails().get(0)).isEqualTo("Unknown error");
    }
}

