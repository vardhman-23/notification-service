package com.demo.notification.service;

import com.demo.notification.api.dto.NotificationStatusResponseDto;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.exception.NotificationNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationQueryServiceUnitTest {

    private NotificationRepository notificationRepository;
    private AuditLogRepository auditLogRepository;
    private NotificationQueryService service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        service = new NotificationQueryService(notificationRepository, auditLogRepository);
    }

    @Test
    @DisplayName("GetNotificationStatus: Throws NotificationNotFoundException when ID does not exist")
    void testNotFound() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNotificationStatus(id))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    @DisplayName("GetNotificationStatus: Computes summary with null attempts")
    void testNullAttempts() {
        UUID id = UUID.randomUUID();
        Notification notification = Notification.builder()
                .notificationId(id)
                .sourceSystem("sys")
                .eventId("evt")
                .idempotencyKey("idem")
                .notificationType("ALERT")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .status(NotificationStatus.ACCEPTED)
                .deliveryAttempts(null)
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(auditLogRepository.findByNotificationIdOrderByTimestampAsc(id)).thenReturn(Collections.emptyList());

        NotificationStatusResponseDto response = service.getNotificationStatus(id);

        assertThat(response.getDeliverySummary().getTotalChannels()).isZero();
        assertThat(response.getDeliverySummary().getTotalAttempts()).isZero();
        assertThat(response.getDeliveryProgress()).isEmpty();
        assertThat(response.getAuditTimeline()).isEmpty();
    }

    @Test
    @DisplayName("GetNotificationStatus: Computes summary with mixed statuses: SENT, FAILED, PENDING, RETRYING")
    void testMixedStatusAttempts() {
        UUID id = UUID.randomUUID();

        DeliveryAttempt a1 = DeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .recipientId("r1")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.SENT)
                .attemptNumber(1)
                .provider("AWS_SES")
                .providerResponseCode("250_OK")
                .executionTime(30L)
                .sentAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        DeliveryAttempt a2 = DeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .recipientId("r1")
                .channel(ChannelType.SMS)
                .status(DeliveryStatus.FAILED)
                .attemptNumber(3)
                .provider("TWILIO")
                .providerResponseCode("429")
                .errorMessage("Rate limit")
                .errorCategory(ErrorCategory.RATE_LIMIT_EXCEEDED)
                .createdAt(Instant.now())
                .build();

        DeliveryAttempt a3 = DeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .recipientId("r2")
                .channel(ChannelType.SLACK)
                .status(DeliveryStatus.PENDING)
                .attemptNumber(1)
                .build();

        DeliveryAttempt a4 = DeliveryAttempt.builder()
                .id(UUID.randomUUID())
                .recipientId("r3")
                .channel(ChannelType.IN_APP)
                .status(DeliveryStatus.RETRYING)
                .attemptNumber(2)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .sourceSystem("sys")
                .eventId("evt")
                .idempotencyKey("idem")
                .notificationType("ALERT")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.DELIVERING)
                .deliveryAttempts(List.of(a1, a2, a3, a4))
                .build();

        AuditLog audit = AuditLog.builder()
                .id(UUID.randomUUID())
                .action(AuditAction.ACCEPTED)
                .metadataReason("Accepted")
                .sanitizedPayloadSummary("Summary")
                .timestamp(Instant.now())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(auditLogRepository.findByNotificationIdOrderByTimestampAsc(id)).thenReturn(List.of(audit));

        NotificationStatusResponseDto response = service.getNotificationStatus(id);

        assertThat(response.getDeliverySummary().getTotalChannels()).isEqualTo(4);
        assertThat(response.getDeliverySummary().getTotalAttempts()).isEqualTo(1 + 3 + 1 + 2); // 7
        assertThat(response.getDeliverySummary().getSuccessfulCount()).isEqualTo(1);
        assertThat(response.getDeliverySummary().getFailedCount()).isEqualTo(1);
        assertThat(response.getDeliverySummary().getPendingCount()).isEqualTo(1);
        assertThat(response.getDeliverySummary().getRetryingCount()).isEqualTo(1);

        assertThat(response.getDeliveryProgress()).hasSize(4);
        assertThat(response.getAuditTimeline()).hasSize(1);
        assertThat(response.getAuditTimeline().get(0).getAction()).isEqualTo(AuditAction.ACCEPTED);
    }
}

