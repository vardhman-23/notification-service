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
 * Detailed status view representing the aggregate delivery state, attempt breakdown,
 * and immutable chronological audit trail for a notification.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Comprehensive status response containing delivery progress and audit trail")
public class NotificationStatusResponseDto {

    @Schema(description = "Notification unique identifier", example = "8a424827-6d86-429f-8317-6b38a47c6f81")
    private UUID notificationId;

    @Schema(description = "Source system identifier", example = "trading-platform")
    private String sourceSystem;

    @Schema(description = "Business event identifier", example = "trade-evt-100")
    private String eventId;

    @Schema(description = "Client idempotency key", example = "idem-margin-100")
    private String idempotencyKey;

    @Schema(description = "Notification classification", example = "MARGIN_CALL")
    private String notificationType;

    @Schema(description = "Severity level", example = "CRITICAL")
    private Severity severity;

    @Schema(description = "Priority level", example = "URGENT")
    private Priority priority;

    @Schema(description = "Aggregate notification status", example = "DELIVERED")
    private NotificationStatus aggregateStatus;

    @Schema(description = "Message subject line", example = "Immediate Margin Call Breach Notice")
    private String subject;

    @Schema(description = "Message body", example = "Your portfolio margin deposit is required within 60 minutes.")
    private String body;

    @Schema(description = "Scheduled delivery timestamp in UTC")
    private Instant scheduledAt;

    @Schema(description = "Expiration timestamp in UTC")
    private Instant expiresAt;

    @Schema(description = "Creation timestamp in UTC")
    private Instant createdAt;

    @Schema(description = "Last update timestamp in UTC")
    private Instant updatedAt;

    @Schema(description = "High-level aggregate delivery counters")
    private DeliverySummaryDto deliverySummary;

    @Schema(description = "Detailed channel-by-channel delivery progress and attempt logs")
    private List<DeliveryProgressDto> deliveryProgress;

    @Schema(description = "Chronologically ordered audit trail tracking all state transitions")
    private List<AuditTimelineEntryDto> auditTimeline;
}
