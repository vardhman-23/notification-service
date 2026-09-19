package com.demo.notification;

import com.demo.notification.delivery.DeliveryWorker;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.service.RoutingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DeliveryWorkerResilienceTest {

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Autowired
    private RoutingService routingService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @Transactional
    @DisplayName("Successful delivery marks attempt SENT, notification DELIVERED, and logs AuditAction.DELIVERED")
    void testSuccessfulDelivery_RecordsDeliveredStatusAndAudit() {
        Notification notification = Notification.builder()
                .sourceSystem("portfolio-service")
                .eventId("evt-success-" + UUID.randomUUID())
                .idempotencyKey("idem-success-" + UUID.randomUUID())
                .notificationType("PORTFOLIO_ALERT")
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .subject("Portfolio Performance Update")
                .body("Your monthly return was +2.8%")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_success")
                .destination("client@example.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Stage delivery attempts via routing
        routingService.routeNotification(saved);

        // Execute delivery
        deliveryWorker.processDelivery(saved.getNotificationId());

        // Verify updated notification state
        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DELIVERED);

        // Verify delivery attempt was marked SENT
        List<DeliveryAttempt> attempts = updated.getDeliveryAttempts();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(attempts.get(0).getProviderResponseCode()).isEqualTo("250_OK");
        assertThat(attempts.get(0).getSentAt()).isNotNull();

        // Verify audit trail contains DELIVERED action
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.DELIVERED);
    }

    @Test
    @Transactional
    @DisplayName("Transient failure (HTTP 429 rate limit) triggers bounded retry and records RETRY_SCHEDULED")
    void testTransientFailure_TriggersBoundedRetryAndRecordsRetryScheduled() {
        Notification notification = Notification.builder()
                .sourceSystem("trading-platform")
                .eventId("evt-transient-" + UUID.randomUUID())
                .idempotencyKey("idem-transient-" + UUID.randomUUID())
                .notificationType("RATE_LIMIT_ALERT")
                .severity(Severity.MEDIUM)
                .priority(Priority.HIGH)
                .subject("Transient Test Alert")
                .body("Testing rate limit backoff")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_transient")
                .destination("transient-429@test.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        List<DeliveryAttempt> attempts = updated.getDeliveryAttempts();
        assertThat(attempts).hasSize(1);
        DeliveryAttempt attempt = attempts.get(0);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT_EXCEEDED);
        assertThat(attempt.getAttemptNumber()).isGreaterThanOrEqualTo(3);

        // Verify that RETRY_SCHEDULED was recorded in AuditLog
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.ROUTED_TO_DEAD_LETTER);
    }

    @Test
    @Transactional
    @DisplayName("Permanent failure (HTTP 400 invalid recipient) terminates immediately on attempt 1 without retry")
    void testPermanentFailure_TerminatesImmediatelyWithoutRetry() {
        Notification notification = Notification.builder()
                .sourceSystem("crm-service")
                .eventId("evt-perm-400-" + UUID.randomUUID())
                .idempotencyKey("idem-perm-400-" + UUID.randomUUID())
                .notificationType("ACCOUNT_NOTICE")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .subject("Account Notice")
                .body("Important update")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_invalid")
                .destination("permanent-400-bad-syntax")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        List<DeliveryAttempt> attempts = updated.getDeliveryAttempts();
        assertThat(attempts).hasSize(1);
        DeliveryAttempt attempt = attempts.get(0);
        // Attempt count must strictly remain 1 (no retries!)
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_RECIPIENT);
        assertThat(attempt.getProviderResponseCode()).isEqualTo("400");

        // Verify AuditLog recorded FAILED with failure reason without any RETRY_SCHEDULED
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).noneMatch(log -> log.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.FAILED
                && log.getMetadataReason().contains("Permanent provider rejection"));
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.ROUTED_TO_DEAD_LETTER);
    }

    @Test
    @Transactional
    @DisplayName("Permanent auth failure (HTTP 401) terminates immediately on attempt 1")
    void testPermanentAuthFailure_TerminatesImmediately() {
        Notification notification = Notification.builder()
                .sourceSystem("security-service")
                .eventId("evt-perm-401-" + UUID.randomUUID())
                .idempotencyKey("idem-perm-401-" + UUID.randomUUID())
                .notificationType("SECURITY_ALERT")
                .severity(Severity.HIGH)
                .priority(Priority.URGENT)
                .subject("Security Token Expired")
                .body("Auth credentials rejected")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_auth_fail")
                .destination("auth-401@test.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        List<DeliveryAttempt> attempts = updated.getDeliveryAttempts();
        assertThat(attempts.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(attempts.get(0).getErrorCategory()).isEqualTo(ErrorCategory.AUTH_ERROR);
        assertThat(attempts.get(0).getProviderResponseCode()).isEqualTo("401");

        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).noneMatch(log -> log.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.FAILED
                && log.getMetadataReason().contains("HTTP 401"));
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.ROUTED_TO_DEAD_LETTER);
    }
}

