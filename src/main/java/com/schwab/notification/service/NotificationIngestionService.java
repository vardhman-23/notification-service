package com.schwab.notification.service;

import com.schwab.notification.api.dto.DeliveryAttemptResponseDto;
import com.schwab.notification.api.dto.NotificationRequestDto;
import com.schwab.notification.api.dto.NotificationResponseDto;
import com.schwab.notification.api.dto.RecipientResponseDto;
import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationIngestionService {

    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public IngestionResult ingestNotification(NotificationRequestDto request) {
        // 1. Check if an identical submission already exists by idempotency key
        Optional<Notification> existingOpt = notificationRepository
                .findBySourceSystemAndEventIdAndIdempotencyKey(
                        request.getSourceSystem(),
                        request.getEventId(),
                        request.getIdempotencyKey()
                );

        if (existingOpt.isEmpty() && request.getIdempotencyKey() != null) {
            existingOpt = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey());
        }

        if (existingOpt.isPresent()) {
            Notification existing = existingOpt.get();
            log.info("Duplicate submission detected for idempotencyKey='{}', returning existing notificationId='{}'",
                    request.getIdempotencyKey(), existing.getNotificationId());

            // Record duplicate suppression in audit log without creating duplicate deliveries
            AuditLog suppressionLog = AuditLog.builder()
                    .notificationId(existing.getNotificationId())
                    .action(AuditAction.SUPPRESSED_DUPLICATE)
                    .metadataReason("Duplicate submission suppressed for idempotency key: " + request.getIdempotencyKey())
                    .sanitizedPayloadSummary(buildSanitizedSummary(request))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(suppressionLog);

            return new IngestionResult(mapToDto(existing), true);
        }

        // 2. New submission: Persist notification with status ACCEPTED
        try {
            Notification notification = Notification.builder()
                    .sourceSystem(request.getSourceSystem())
                    .eventId(request.getEventId())
                    .idempotencyKey(request.getIdempotencyKey())
                    .notificationType(request.getNotificationType())
                    .severity(request.getSeverity())
                    .priority(request.getPriority())
                    .status(NotificationStatus.ACCEPTED)
                    .subject(request.getSubject())
                    .body(request.getBody())
                    .scheduledAt(request.getScheduledAt())
                    .expiresAt(request.getExpiresAt())
                    .build();

            if (request.getRecipients() != null) {
                request.getRecipients().forEach(r -> {
                    NotificationRecipient recipient = NotificationRecipient.builder()
                            .recipientId(r.getRecipientId())
                            .destination(r.getDestination())
                            .preferredChannels(r.getPreferredChannels())
                            .optedOutChannels(r.getOptedOutChannels())
                            .quietHoursStart(r.getQuietHoursStart())
                            .quietHoursEnd(r.getQuietHoursEnd())
                            .build();
                    notification.addRecipient(recipient);
                });
            }

            Notification saved = notificationRepository.save(notification);

            // 3. Write initial AuditLog entry
            AuditLog initialAuditLog = AuditLog.builder()
                    .notificationId(saved.getNotificationId())
                    .action(AuditAction.ACCEPTED)
                    .metadataReason("Notification accepted from source: " + saved.getSourceSystem())
                    .sanitizedPayloadSummary(buildSanitizedSummary(request))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(initialAuditLog);

            log.info("New notification successfully accepted with id='{}'", saved.getNotificationId());
            return new IngestionResult(mapToDto(saved), false);

        } catch (DataIntegrityViolationException ex) {
            // Concurrent submission collision handled gracefully
            log.warn("Concurrent submission detected on composite idempotency key for sourceSystem='{}', eventId='{}'",
                    request.getSourceSystem(), request.getEventId());
            Notification existing = notificationRepository
                    .findBySourceSystemAndEventIdAndIdempotencyKey(
                            request.getSourceSystem(),
                            request.getEventId(),
                            request.getIdempotencyKey()
                    )
                    .or(() -> notificationRepository.findByIdempotencyKey(request.getIdempotencyKey()))
                    .orElseThrow(() -> ex);

            return new IngestionResult(mapToDto(existing), true);
        }
    }

    public NotificationResponseDto mapToDto(Notification entity) {
        List<RecipientResponseDto> recipients = entity.getRecipients() == null ? Collections.emptyList() :
                entity.getRecipients().stream()
                        .map(r -> RecipientResponseDto.builder()
                                .id(r.getId())
                                .recipientId(r.getRecipientId())
                                .destination(r.getDestination())
                                .preferredChannels(r.getPreferredChannels())
                                .optedOutChannels(r.getOptedOutChannels())
                                .quietHoursStart(r.getQuietHoursStart())
                                .quietHoursEnd(r.getQuietHoursEnd())
                                .createdAt(r.getCreatedAt())
                                .build())
                        .collect(Collectors.toList());

        List<DeliveryAttemptResponseDto> deliveryAttempts = entity.getDeliveryAttempts() == null ? Collections.emptyList() :
                entity.getDeliveryAttempts().stream()
                        .map(d -> DeliveryAttemptResponseDto.builder()
                                .id(d.getId())
                                .recipientId(d.getRecipientId())
                                .channel(d.getChannel())
                                .attemptNumber(d.getAttemptNumber())
                                .status(d.getStatus())
                                .provider(d.getProvider())
                                .providerResponseCode(d.getProviderResponseCode())
                                .errorMessage(d.getErrorMessage())
                                .executionTime(d.getExecutionTime())
                                .errorCategory(d.getErrorCategory())
                                .createdAt(d.getCreatedAt())
                                .build())
                        .collect(Collectors.toList());

        return NotificationResponseDto.builder()
                .notificationId(entity.getNotificationId())
                .sourceSystem(entity.getSourceSystem())
                .eventId(entity.getEventId())
                .idempotencyKey(entity.getIdempotencyKey())
                .notificationType(entity.getNotificationType())
                .severity(entity.getSeverity())
                .priority(entity.getPriority())
                .status(entity.getStatus())
                .subject(entity.getSubject())
                .body(entity.getBody())
                .scheduledAt(entity.getScheduledAt())
                .expiresAt(entity.getExpiresAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .recipients(recipients)
                .deliveryAttempts(deliveryAttempts)
                .build();
    }

    private String buildSanitizedSummary(NotificationRequestDto request) {
        int recipientCount = request.getRecipients() != null ? request.getRecipients().size() : 0;
        return String.format("Type: %s, Severity: %s, Priority: %s, Recipients: %d",
                request.getNotificationType(), request.getSeverity(), request.getPriority(), recipientCount);
    }
}

