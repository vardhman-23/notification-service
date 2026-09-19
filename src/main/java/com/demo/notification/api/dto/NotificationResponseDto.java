package com.demo.notification.api.dto;

import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response payload returned upon notification submission or query.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Notification submission acknowledgment and status")
public class NotificationResponseDto {

    @Schema(description = "Unique UUID identifier of the notification", example = "8a424827-6d86-429f-8317-6b38a47c6f81")
    private UUID notificationId;

    @Schema(description = "Identifier of the calling source system", example = "trading-platform")
    private String sourceSystem;

    @Schema(description = "Unique event identifier from the source system", example = "trade-evt-100")
    private String eventId;

    @Schema(description = "Client-supplied idempotency key", example = "idem-margin-100")
    private String idempotencyKey;

    @Schema(description = "Business classification of the notification", example = "MARGIN_CALL")
    private String notificationType;

    @Schema(description = "Severity level", example = "CRITICAL")
    private Severity severity;

    @Schema(description = "Dispatch priority", example = "URGENT")
    private Priority priority;

    @Schema(description = "Current lifecycle status", example = "ACCEPTED")
    private NotificationStatus status;

    @Schema(description = "Notification subject line", example = "Immediate Margin Call Breach Notice")
    private String subject;

    @Schema(description = "Notification message body", example = "Your portfolio margin deposit is required within 60 minutes.")
    private String body;

    @Schema(description = "Scheduled UTC delivery timestamp")
    private Instant scheduledAt;

    @Schema(description = "Expiration UTC timestamp")
    private Instant expiresAt;

    @Schema(description = "Creation timestamp in UTC")
    private Instant createdAt;

    @Schema(description = "Last update timestamp in UTC")
    private Instant updatedAt;

    @Schema(description = "List of associated recipients")
    private List<RecipientResponseDto> recipients;

    @Schema(description = "List of delivery attempts executed or staged")
    private List<DeliveryAttemptResponseDto> deliveryAttempts;
}
