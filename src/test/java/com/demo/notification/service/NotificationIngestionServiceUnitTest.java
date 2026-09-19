package com.demo.notification.service;

import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.NotificationResponseDto;
import com.demo.notification.api.dto.RecipientRequestDto;
import com.demo.notification.delivery.NotificationAcceptedEvent;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationIngestionServiceUnitTest {

    private NotificationRepository notificationRepository;
    private AuditLogRepository auditLogRepository;
    private ApplicationEventPublisher eventPublisher;
    private NotificationIngestionService service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new NotificationIngestionService(notificationRepository, auditLogRepository, eventPublisher);
    }

    @Test
    @DisplayName("Ingest: Returns duplicate true when found by idempotencyKey only")
    void testDuplicateByIdempotencyKeyOnly() {
        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("source-a")
                .eventId("event-1")
                .idempotencyKey("idem-key-1")
                .notificationType("ALERT")
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .body("body")
                .build();

        Notification existing = Notification.builder()
                .notificationId(UUID.randomUUID())
                .sourceSystem("source-a")
                .eventId("event-old")
                .idempotencyKey("idem-key-1")
                .notificationType("ALERT")
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .body("old body")
                .build();

        when(notificationRepository.findBySourceSystemAndEventIdAndIdempotencyKey("source-a", "event-1", "idem-key-1"))
                .thenReturn(Optional.empty());
        when(notificationRepository.findByIdempotencyKey("idem-key-1"))
                .thenReturn(Optional.of(existing));

        IngestionResult result = service.ingestNotification(request);

        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.getNotification().getNotificationId()).isEqualTo(existing.getNotificationId());
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("Ingest: Handles concurrent collision and recovers existing record")
    void testConcurrentCollisionRecovery() {
        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("source-b")
                .eventId("event-2")
                .idempotencyKey("idem-key-2")
                .notificationType("ALERT")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .body("test body")
                .build();

        Notification existing = Notification.builder()
                .notificationId(UUID.randomUUID())
                .sourceSystem("source-b")
                .eventId("event-2")
                .idempotencyKey("idem-key-2")
                .notificationType("ALERT")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .body("recovered body")
                .build();

        when(notificationRepository.findBySourceSystemAndEventIdAndIdempotencyKey("source-b", "event-2", "idem-key-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key violation"));

        IngestionResult result = service.ingestNotification(request);

        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.getNotification().getNotificationId()).isEqualTo(existing.getNotificationId());
    }

    @Test
    @DisplayName("Ingest: Re-throws DataIntegrityViolationException if existing record is not found")
    void testConcurrentCollisionThrowsIfNotRecoverable() {
        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("source-c")
                .eventId("event-3")
                .idempotencyKey("idem-key-3")
                .notificationType("ALERT")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .body("test")
                .build();

        when(notificationRepository.findBySourceSystemAndEventIdAndIdempotencyKey("source-c", "event-3", "idem-key-3"))
                .thenReturn(Optional.empty());
        when(notificationRepository.findByIdempotencyKey("idem-key-3"))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("Unrecoverable DB error"));

        assertThatThrownBy(() -> service.ingestNotification(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Ingest: New submission with null recipients list")
    void testNewSubmissionNullRecipients() {
        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("source-d")
                .eventId("event-4")
                .idempotencyKey("idem-key-4")
                .notificationType("ALERT")
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .body("test message")
                .recipients(null)
                .build();

        UUID id = UUID.randomUUID();
        Notification saved = Notification.builder()
                .notificationId(id)
                .sourceSystem("source-d")
                .eventId("event-4")
                .idempotencyKey("idem-key-4")
                .notificationType("ALERT")
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .body("test message")
                .recipients(new ArrayList<>())
                .build();

        when(notificationRepository.findBySourceSystemAndEventIdAndIdempotencyKey("source-d", "event-4", "idem-key-4"))
                .thenReturn(Optional.empty());
        when(notificationRepository.findByIdempotencyKey("idem-key-4"))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

        IngestionResult result = service.ingestNotification(request);

        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.getNotification().getNotificationId()).isEqualTo(id);
        verify(eventPublisher).publishEvent(any(NotificationAcceptedEvent.class));
    }

    @Test
    @DisplayName("MapToDto: Maps populated delivery attempts and recipients")
    void testMapToDtoPopulated() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .id(UUID.randomUUID())
                .recipientId("r1")
                .destination("dest@example.com")
                .preferredChannels("EMAIL")
                .optedOutChannels("SMS")
                .quietHoursStart(22)
                .quietHoursEnd(7)
                .createdAt(Instant.now())
                .build();

        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .recipientId("r1")
                .channel(ChannelType.EMAIL)
                .attemptNumber(1)
                .status(DeliveryStatus.SENT)
                .provider("AWS_SES")
                .providerResponseCode("250_OK")
                .errorMessage(null)
                .executionTime(50L)
                .errorCategory(null)
                .createdAt(Instant.now())
                .build();

        Notification notification = Notification.builder()
                .notificationId(UUID.randomUUID())
                .sourceSystem("sys")
                .eventId("evt")
                .idempotencyKey("idem")
                .notificationType("ALERT")
                .severity(Severity.CRITICAL)
                .priority(Priority.URGENT)
                .status(NotificationStatus.DELIVERED)
                .subject("Subject")
                .body("Body")
                .scheduledAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .recipients(List.of(recipient))
                .deliveryAttempts(List.of(attempt))
                .build();

        NotificationResponseDto dto = service.mapToDto(notification);

        assertThat(dto.getRecipients()).hasSize(1);
        assertThat(dto.getRecipients().get(0).getRecipientId()).isEqualTo("r1");
        assertThat(dto.getDeliveryAttempts()).hasSize(1);
        assertThat(dto.getDeliveryAttempts().get(0).getProvider()).isEqualTo("AWS_SES");
    }
}

