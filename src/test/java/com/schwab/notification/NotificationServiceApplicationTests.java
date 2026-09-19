package com.schwab.notification;

import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.DeliveryAttempt;
import com.schwab.notification.domain.model.IdempotencyRecord;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.DeliveryAttemptRepository;
import com.schwab.notification.domain.repository.IdempotencyRecordRepository;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.ChannelType;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.domain.types.ErrorCategory;
import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.domain.types.Priority;
import com.schwab.notification.domain.types.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class NotificationServiceApplicationTests {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    @DisplayName("Spring Application Context loads successfully")
    void contextLoads() {
        assertThat(notificationRepository).isNotNull();
        assertThat(deliveryAttemptRepository).isNotNull();
        assertThat(auditLogRepository).isNotNull();
        assertThat(idempotencyRecordRepository).isNotNull();
    }

    @Test
    @Transactional
    @DisplayName("Should successfully persist and retrieve Notification aggregate conforming to Prompt 1.2")
    void testNotificationPersistence() {
        Notification notification = Notification.builder()
                .eventId("evt-trade-12345")
                .sourceSystem("trading-platform")
                .idempotencyKey("idem-key-001")
                .notificationType("MARGIN_CALL")
                .severity(Severity.CRITICAL)
                .priority(Priority.URGENT)
                .status(NotificationStatus.ACCEPTED)
                .subject("Critical Account Margin Call Alert")
                .body("Your account margin requirement has dropped below threshold.")
                .expiresAt(Instant.now().plus(2, ChronoUnit.HOURS))
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("trader_001")
                .destination("trader@schwab.com")
                .preferredChannels("EMAIL,SMS")
                .build();

        notification.addRecipient(recipient);

        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .recipientId("trader_001")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.SENT)
                .attemptNumber(1)
                .providerResponseCode("250_OK")
                .executionTime(145L)
                .build();

        notification.addDeliveryAttempt(attempt);

        Notification saved = notificationRepository.save(notification);

        assertThat(saved.getNotificationId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getRecipients()).hasSize(1);
        assertThat(saved.getDeliveryAttempts()).hasSize(1);

        Optional<Notification> retrieved = notificationRepository.findById(saved.getNotificationId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(retrieved.get().getStatus()).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(retrieved.get().getRecipients().get(0).getDestination()).isEqualTo("trader@schwab.com");

        DeliveryAttempt retrievedAttempt = retrieved.get().getDeliveryAttempts().get(0);
        assertThat(retrievedAttempt.getProviderResponseCode()).isEqualTo("250_OK");
        assertThat(retrievedAttempt.getExecutionTime()).isEqualTo(145L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Should enforce composite unique constraint for idempotency: sourceSystem + eventId + idempotencyKey")
    void testCompositeIdempotencyConstraint() {
        String sourceSystem = "risk-engine";
        String eventId = "evt-risk-888";
        String idempotencyKey = "idem-unique-risk-888";

        Notification notification1 = Notification.builder()
                .sourceSystem(sourceSystem)
                .eventId(eventId)
                .idempotencyKey(idempotencyKey)
                .notificationType("PORTFOLIO_EXPOSURE")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .body("Risk limit reached")
                .build();

        Notification saved = notificationRepository.saveAndFlush(notification1);
        assertThat(saved.getNotificationId()).isNotNull();

        Notification notification2 = Notification.builder()
                .sourceSystem(sourceSystem)
                .eventId(eventId)
                .idempotencyKey(idempotencyKey) // Identical composite key
                .notificationType("PORTFOLIO_EXPOSURE")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .body("Duplicate risk limit alert")
                .build();

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(notification2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Cleanup
        notificationRepository.delete(saved);
    }

    @Test
    @Transactional
    @DisplayName("Should persist AuditLog with action, metadata/reason, and sanitized payload summary")
    void testAuditLogPersistence() {
        UUID notifId = UUID.randomUUID();
        AuditLog auditLog = AuditLog.builder()
                .notificationId(notifId)
                .action(AuditAction.ACCEPTED)
                .metadataReason("Notification verified and queued for routing")
                .sanitizedPayloadSummary("Type: MARGIN_CALL, RecipientCount: 1, Priority: URGENT")
                .build();

        AuditLog saved = auditLogRepository.save(auditLog);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTimestamp()).isNotNull();
        assertThat(auditLogRepository.findByNotificationIdOrderByTimestampAsc(notifId)).hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("Should persist and look up IdempotencyRecord with UUID notificationId")
    void testIdempotencyRecordPersistence() {
        UUID notifId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey("idemp-unique-999")
                .notificationId(notifId)
                .sourceSystem("risk-engine")
                .requestHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .status("COMPLETED")
                .expiresAt(expiresAt)
                .build();

        idempotencyRecordRepository.save(record);

        Optional<IdempotencyRecord> found = idempotencyRecordRepository
                .findByIdempotencyKeyAndExpiresAtGreaterThan("idemp-unique-999", Instant.now());
        assertThat(found).isPresent();
        assertThat(found.get().getNotificationId()).isEqualTo(notifId);
    }

    @Test
    @DisplayName("ErrorCategory correctly determines retryability")
    void testErrorCategoryRetryability() {
        assertThat(ErrorCategory.TRANSIENT_PROVIDER_FAILURE.isRetryable()).isTrue();
        assertThat(ErrorCategory.RATE_LIMIT_EXCEEDED.isRetryable()).isTrue();
        assertThat(ErrorCategory.TIMEOUT.isRetryable()).isTrue();
        assertThat(ErrorCategory.PERMANENT_PROVIDER_REJECTION.isRetryable()).isFalse();
        assertThat(ErrorCategory.INVALID_RECIPIENT.isRetryable()).isFalse();
        assertThat(ErrorCategory.AUTH_ERROR.isRetryable()).isFalse();
    }
}
