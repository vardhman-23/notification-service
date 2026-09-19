package com.schwab.notification.api.dto;

import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.domain.types.Priority;
import com.schwab.notification.domain.types.Severity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationStatusResponseDto {
    // Aggregate Notification Status
    private UUID notificationId;
    private String sourceSystem;
    private String eventId;
    private String idempotencyKey;
    private String notificationType;
    private Severity severity;
    private Priority priority;
    private NotificationStatus aggregateStatus;
    private String subject;
    private String body;
    private Instant scheduledAt;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    // Delivery Summary Metrics
    private DeliverySummaryDto deliverySummary;

    // Channel-by-Channel Delivery Progress & Attempt Counts
    private List<DeliveryProgressDto> deliveryProgress;

    // Chronological Audit Timeline
    private List<AuditTimelineEntryDto> auditTimeline;
}

