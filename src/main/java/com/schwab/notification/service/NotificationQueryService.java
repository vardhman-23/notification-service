package com.schwab.notification.service;

import com.schwab.notification.api.dto.AuditTimelineEntryDto;
import com.schwab.notification.api.dto.DeliveryProgressDto;
import com.schwab.notification.api.dto.DeliverySummaryDto;
import com.schwab.notification.api.dto.NotificationStatusResponseDto;
import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.DeliveryAttempt;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public NotificationStatusResponseDto getNotificationStatus(UUID id) {
        log.info("Fetching status and audit timeline for notificationId='{}'", id);

        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with ID: " + id));

        // 1. Channel-by-channel delivery progress
        List<DeliveryProgressDto> deliveryProgress = notification.getDeliveryAttempts() == null
                ? Collections.emptyList()
                : notification.getDeliveryAttempts().stream()
                .map(this::mapDeliveryAttempt)
                .collect(Collectors.toList());

        // 2. Summary metrics
        DeliverySummaryDto summary = computeDeliverySummary(notification.getDeliveryAttempts());

        // 3. Chronological audit timeline
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(id);
        List<AuditTimelineEntryDto> timeline = auditLogs.stream()
                .map(this::mapAuditEntry)
                .collect(Collectors.toList());

        return NotificationStatusResponseDto.builder()
                .notificationId(notification.getNotificationId())
                .sourceSystem(notification.getSourceSystem())
                .eventId(notification.getEventId())
                .idempotencyKey(notification.getIdempotencyKey())
                .notificationType(notification.getNotificationType())
                .severity(notification.getSeverity())
                .priority(notification.getPriority())
                .aggregateStatus(notification.getStatus())
                .subject(notification.getSubject())
                .body(notification.getBody())
                .scheduledAt(notification.getScheduledAt())
                .expiresAt(notification.getExpiresAt())
                .createdAt(notification.getCreatedAt())
                .updatedAt(notification.getUpdatedAt())
                .deliverySummary(summary)
                .deliveryProgress(deliveryProgress)
                .auditTimeline(timeline)
                .build();
    }

    private DeliveryProgressDto mapDeliveryAttempt(DeliveryAttempt da) {
        return DeliveryProgressDto.builder()
                .attemptId(da.getId())
                .recipientId(da.getRecipientId())
                .channel(da.getChannel())
                .provider(da.getProvider())
                .status(da.getStatus())
                .attemptNumber(da.getAttemptNumber())
                .providerResponseCode(da.getProviderResponseCode())
                .errorMessage(da.getErrorMessage())
                .executionTimeMs(da.getExecutionTime())
                .errorCategory(da.getErrorCategory())
                .sentAt(da.getSentAt())
                .createdAt(da.getCreatedAt())
                .build();
    }

    private DeliverySummaryDto computeDeliverySummary(List<DeliveryAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return DeliverySummaryDto.builder()
                    .totalChannels(0)
                    .totalAttempts(0)
                    .successfulCount(0)
                    .failedCount(0)
                    .pendingCount(0)
                    .retryingCount(0)
                    .build();
        }

        int totalAttemptsSum = attempts.stream().mapToInt(DeliveryAttempt::getAttemptNumber).sum();
        int successful = (int) attempts.stream().filter(a -> a.getStatus() == DeliveryStatus.SENT).count();
        int failed = (int) attempts.stream().filter(a -> a.getStatus() == DeliveryStatus.FAILED).count();
        int pending = (int) attempts.stream().filter(a -> a.getStatus() == DeliveryStatus.PENDING).count();
        int retrying = (int) attempts.stream().filter(a -> a.getStatus() == DeliveryStatus.RETRYING).count();

        return DeliverySummaryDto.builder()
                .totalChannels(attempts.size())
                .totalAttempts(totalAttemptsSum)
                .successfulCount(successful)
                .failedCount(failed)
                .pendingCount(pending)
                .retryingCount(retrying)
                .build();
    }

    private AuditTimelineEntryDto mapAuditEntry(AuditLog al) {
        return AuditTimelineEntryDto.builder()
                .id(al.getId())
                .action(al.getAction())
                .metadataReason(al.getMetadataReason())
                .sanitizedPayloadSummary(al.getSanitizedPayloadSummary())
                .timestamp(al.getTimestamp())
                .build();
    }
}

