package com.demo.notification.service;

import com.demo.notification.api.dto.DeliveryAttemptResponseDto;
import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.NotificationResponseDto;
import com.demo.notification.api.dto.RecipientResponseDto;
import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.delivery.NotificationAcceptedEvent;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.observability.NotificationMetrics;
import com.demo.notification.util.DataMaskingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service responsible for ingesting, validating, deduplicating, and persisting incoming notifications.
 * <p>
 * Implements the Idempotent Receiver pattern using a composite uniqueness constraint on
 * ({@code sourceSystem}, {@code eventId}, {@code idempotencyKey}). Ensures exactly-once semantics
 * and publishes {@link NotificationAcceptedEvent} with distributed tracing context.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationIngestionService {

    private final NotificationRepository notificationRepository;
    private final NotificationPersistenceService persistenceService;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationMetrics notificationMetrics;

    /**
     * Ingests a notification request with composite deduplication and audit recording.
     * Concurrency collisions are cleanly caught via unique database constraints.
     *
     * @param request the incoming notification request DTO
     * @return {@link IngestionResult} containing mapped DTO and duplicate status flag
     */
    public IngestionResult ingestNotification(NotificationRequestDto request) {
        notificationMetrics.incrementReceived();
        log.debug("Evaluating idempotency for sourceSystem='{}', eventId='{}', idempotencyKey='{}'",
                request.getSourceSystem(), request.getEventId(), request.getIdempotencyKey());

        // 1. Check if an identical submission already exists by composite unique key or idempotencyKey
        Optional<Notification> existingOpt =
                notificationRepository.findBySourceSystemAndEventIdAndIdempotencyKey(
                        request.getSourceSystem(),
                        request.getEventId(),
                        request.getIdempotencyKey()
                );

        if (existingOpt.isEmpty() && request.getIdempotencyKey() != null) {
            existingOpt = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey());
        }

        if (existingOpt.isPresent()) {
            Notification existing = existingOpt.get();
            log.info("Duplicate submission recognized for idempotencyKey='{}'. Suppressing re-execution, existing id='{}'",
                    request.getIdempotencyKey(), existing.getNotificationId());

            // Record duplicate suppression in immutable audit trail
            AuditLog suppressionLog = AuditLog.builder()
                    .notificationId(existing.getNotificationId())
                    .action(AuditAction.SUPPRESSED_DUPLICATE)
                    .metadataReason("Duplicate submission suppressed for idempotency key: " + request.getIdempotencyKey())
                    .sanitizedPayloadSummary(buildSanitizedSummary(request))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(suppressionLog);
            notificationMetrics.incrementDuplicateSuppressed();

            return new IngestionResult(mapToDto(existing), true);
        }

        // 2. New submission: Persist notification entity with status ACCEPTED
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

            Notification saved = persistenceService.saveAndFlush(notification);

            // 3. Record initial AuditLog entry (tamper-evident audit trail with sanitized summary)
            AuditLog initialAuditLog = AuditLog.builder()
                    .notificationId(saved.getNotificationId())
                    .action(AuditAction.ACCEPTED)
                    .metadataReason("Notification accepted from source: " + saved.getSourceSystem())
                    .sanitizedPayloadSummary(buildSanitizedSummary(request))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(initialAuditLog);

            // 4. Propagate cross-thread correlation ID across async boundary
            String correlationId = MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            eventPublisher.publishEvent(new NotificationAcceptedEvent(saved.getNotificationId(), correlationId));

            log.info("Notification accepted and persisted successfully with id='{}', correlationId='{}'",
                    saved.getNotificationId(), correlationId);
            return new IngestionResult(mapToDto(saved), false);

        } catch (DataIntegrityViolationException ex) {
            // Concurrent submission collision handled gracefully via unique database constraint
            log.warn("Concurrent submission collision on composite unique index for source='{}', event='{}', idempotencyKey='{}'. Resolving to winner row.",
                    request.getSourceSystem(), request.getEventId(), request.getIdempotencyKey());

            Notification existing = notificationRepository
                    .findBySourceSystemAndEventIdAndIdempotencyKey(
                            request.getSourceSystem(),
                            request.getEventId(),
                            request.getIdempotencyKey()
                    )
                    .or(() -> notificationRepository.findByIdempotencyKey(request.getIdempotencyKey()))
                    .orElseThrow(() -> ex);

            AuditLog suppressionLog = AuditLog.builder()
                    .notificationId(existing.getNotificationId())
                    .action(AuditAction.SUPPRESSED_DUPLICATE)
                    .metadataReason("Race condition resolved via composite unique constraint for key: " + request.getIdempotencyKey())
                    .sanitizedPayloadSummary(buildSanitizedSummary(request))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(suppressionLog);
            notificationMetrics.incrementDuplicateSuppressed();

            return new IngestionResult(mapToDto(existing), true);
        }
    }

    /**
     * Converts a JPA {@link Notification} domain entity into a client-facing {@link NotificationResponseDto}.
     *
     * @param entity the notification domain aggregate
     * @return mapped response DTO
     */
    public NotificationResponseDto mapToDto(Notification entity) {
        List<RecipientResponseDto> recipients = (entity.getRecipients() != null && org.hibernate.Hibernate.isInitialized(entity.getRecipients()))
                ? entity.getRecipients().stream()
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
                        .collect(Collectors.toList())
                : Collections.emptyList();

        List<DeliveryAttemptResponseDto> deliveryAttempts = (entity.getDeliveryAttempts() != null && org.hibernate.Hibernate.isInitialized(entity.getDeliveryAttempts()))
                ? entity.getDeliveryAttempts().stream()
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
                        .collect(Collectors.toList())
                : Collections.emptyList();

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

    /**
     * Constructs a privacy-compliant, PII-free summary for audit trail logging.
     *
     * @param request incoming submission DTO
     * @return sanitized summary string omitting message body and personal details
     */
    private String buildSanitizedSummary(NotificationRequestDto request) {
        int recipientCount = request.getRecipients() != null ? request.getRecipients().size() : 0;
        String sanitizedSubject = DataMaskingUtils.maskSensitiveContent(request.getSubject());
        return String.format("Type: %s, Severity: %s, Priority: %s, Recipients: %d, Subject: %s",
                request.getNotificationType(), request.getSeverity(), request.getPriority(), recipientCount, sanitizedSubject);
    }
}
