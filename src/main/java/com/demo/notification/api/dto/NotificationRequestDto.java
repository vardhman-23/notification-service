package com.demo.notification.api.dto;

import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/**
 * Request payload for submitting a new notification or alert request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Notification submission request payload")
public class NotificationRequestDto {

    @NotBlank(message = "Source system identifier is required")
    @Schema(description = "Identifier of the calling source system", example = "trading-platform")
    private String sourceSystem;

    @NotBlank(message = "Event identifier is required")
    @Schema(description = "Unique event identifier from the source system", example = "trade-evt-100")
    private String eventId;

    @NotBlank(message = "Idempotency key is required")
    @Schema(description = "Unique idempotency key for deduplication", example = "idem-margin-100")
    private String idempotencyKey;

    @NotBlank(message = "Notification type is required")
    @Schema(description = "Business classification of the notification", example = "MARGIN_CALL")
    private String notificationType;

    @NotNull(message = "Severity is required")
    @Schema(description = "Notification severity level", example = "CRITICAL")
    private Severity severity;

    @NotNull(message = "Priority is required")
    @Schema(description = "Notification dispatch priority", example = "URGENT")
    private Priority priority;

    @Size(max = 255, message = "Subject must not exceed 255 characters")
    @Schema(description = "Brief title or subject line", example = "Immediate Margin Call Breach Notice")
    private String subject;

    @NotBlank(message = "Notification body is required")
    @Schema(description = "Detailed message body or template data", example = "Your portfolio margin deposit is required within 60 minutes.")
    private String body;

    @NotEmpty(message = "At least one recipient must be specified")
    @Valid
    @Schema(description = "List of target recipients and their delivery preferences")
    private List<RecipientRequestDto> recipients;

    @Schema(description = "Optional future scheduled delivery timestamp in UTC")
    private Instant scheduledAt;

    @Schema(description = "Optional expiration timestamp after which delivery should not be attempted")
    private Instant expiresAt;
}
