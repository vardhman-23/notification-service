package com.demo.notification;

import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.service.RoutingResult;
import com.demo.notification.service.RoutingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RoutingServiceTest {

    @Autowired
    private RoutingService routingService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @Transactional
    @DisplayName("CRITICAL severity forces both EMAIL and SMS delivery attempts regardless of recipient preferences")
    void testCriticalSeverity_ForcesSmsAndEmail() {
        Notification notification = Notification.builder()
                .sourceSystem("trading-platform")
                .eventId("evt-margin-call-" + UUID.randomUUID())
                .idempotencyKey("idem-crit-" + UUID.randomUUID())
                .notificationType("MARGIN_CALL")
                .severity(Severity.CRITICAL)
                .priority(Priority.URGENT)
                .status(NotificationStatus.ACCEPTED)
                .subject("Immediate Margin Deposit Required")
                .body("Your account equity has breached maintenance margin limits.")
                .build();

        // Recipient preference only requested EMAIL
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("trader_99")
                .destination("trader99@example.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Execute routing
        RoutingResult result = routingService.routeNotification(saved);

        // Verify notification transitioned to ROUTED
        assertThat(result.getNotification().getStatus()).isEqualTo(NotificationStatus.ROUTED);

        // Verify recipient was assigned both EMAIL and SMS
        Set<ChannelType> channels = result.getRecipientChannels().get("trader_99");
        assertThat(channels).containsExactlyInAnyOrder(ChannelType.EMAIL, ChannelType.SMS);

        // Verify 2 DeliveryAttempt entities were staged
        List<DeliveryAttempt> attempts = result.getNotification().getDeliveryAttempts();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(DeliveryAttempt::getChannel)
                .containsExactlyInAnyOrder(ChannelType.EMAIL, ChannelType.SMS);
        assertThat(attempts).extracting(DeliveryAttempt::getProvider)
                .containsExactlyInAnyOrder("AWS_SES", "TWILIO");

        // Verify AuditLog recorded the routing decision
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.ROUTED
                && log.getMetadataReason().contains("Severity CRITICAL forced channels"));
    }

    @Test
    @Transactional
    @DisplayName("Non-critical severity respects recipient preferences (e.g. SMS only)")
    void testNonCriticalSeverity_RespectsRecipientPreferences() {
        Notification notification = Notification.builder()
                .sourceSystem("account-service")
                .eventId("evt-account-" + UUID.randomUUID())
                .idempotencyKey("idem-sms-" + UUID.randomUUID())
                .notificationType("PASSWORD_CHANGED")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .subject("Security Notification")
                .body("Your password was updated.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_50")
                .destination("+14155552671")
                .preferredChannels("SMS")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        RoutingResult result = routingService.routeNotification(saved);

        Set<ChannelType> channels = result.getRecipientChannels().get("client_50");
        assertThat(channels).containsExactly(ChannelType.SMS);

        List<DeliveryAttempt> attempts = result.getNotification().getDeliveryAttempts();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getChannel()).isEqualTo(ChannelType.SMS);
        assertThat(attempts.get(0).getProvider()).isEqualTo("TWILIO");
    }

    @Test
    @Transactional
    @DisplayName("Missing recipient preferences fallback to EMAIL channel")
    void testMissingPreferences_FallbackToEmail() {
        Notification notification = Notification.builder()
                .sourceSystem("news-service")
                .eventId("evt-news-" + UUID.randomUUID())
                .idempotencyKey("idem-news-" + UUID.randomUUID())
                .notificationType("MARKET_SUMMARY")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .status(NotificationStatus.ACCEPTED)
                .subject("Daily Market Close")
                .body("S&P 500 closed up 0.4%")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_60")
                .destination("client60@example.com")
                .preferredChannels(null) // No preference
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        RoutingResult result = routingService.routeNotification(saved);

        Set<ChannelType> channels = result.getRecipientChannels().get("client_60");
        assertThat(channels).containsExactly(ChannelType.EMAIL);

        List<DeliveryAttempt> attempts = result.getNotification().getDeliveryAttempts();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getChannel()).isEqualTo(ChannelType.EMAIL);
        assertThat(attempts.get(0).getProvider()).isEqualTo("AWS_SES");
    }
}

