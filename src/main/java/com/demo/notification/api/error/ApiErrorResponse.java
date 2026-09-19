package com.demo.notification.api.error;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error payload returned across all API failure responses.
 * <p>
 * Complies with REST error reporting standards and includes the distributed tracing
 * {@code correlationId} to facilitate debugging and cross-system support.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Standardized error response model")
public class ApiErrorResponse {

    @Schema(description = "HTTP status code", example = "400")
    private int status;

    @Schema(description = "HTTP status reason phrase", example = "Bad Request")
    private String error;

    @Schema(description = "High-level human-readable error summary", example = "Validation failed for input payload")
    private String message;

    @Schema(description = "Request URI that resulted in the error", example = "/api/v1/notifications")
    private String path;

    @Schema(description = "Distributed tracing correlation ID", example = "903677c0-6b24-4e9e-ac7a-949b2577a607")
    private String correlationId;

    @Schema(description = "Specific validation or diagnostic error details")
    private List<String> details;

    @Schema(description = "UTC timestamp when the error occurred")
    private Instant timestamp;
}
