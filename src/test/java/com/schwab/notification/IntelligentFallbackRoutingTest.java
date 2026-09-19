package com.schwab.notification;

import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.ChannelType;
import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.domain.types.Priority;
import com.schwab.notification.domain.types.Severity;
import com.schwab.notification.service.RoutingResult;
import com.schwab.notification.service.RoutingService;
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
class IntelligentFallbackRoutingTest {

    @Autowired
    private RoutingService routingService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @Transactional
    @DisplayName("Tier 1: CRITICAL severity overrides user opt-out and quiet-hour restriction with REGULATORY_OVERRIDE_APPLIED audit")
    void testTier1_RegulatoryOverride_CriticalAlertOverridesOptOutAndQuietHours() {
        Notification notification = Notification.builder()
                .sourceSystem("risk-engine")
                .eventId("evt-margin-call-" + UUID.randomUUID())
                .idempotencyKey("idem-override-" + UUID.randomUUID())
                .notificationType("MARGIN_CALL")
                .severity(Severity.CRITICAL)
                .priority(Priority.URGENT)
                .status(NotificationStatus.ACCEPTED)
                .subject("Immediate Margin Call Action Required")
                .body("Your account has exceeded maximum allowable portfolio leverage.")
                .build();

        // Recipient opted out of SMS and has active quiet hours 22:00 - 07:00
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("trader_vip")
                .destination("trader_vip@schwab.com")
                .preferredChannels("EMAIL")
                .optedOutChannels("SMS")
                .quietHoursStart(22)
                .quietHoursEnd(7)
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Evaluate at 02:00 (2 AM, within quiet hours)
        RoutingResult result = routingService.routeNotification(saved, 2);

        // Both EMAIL and SMS must be forced due to Tier 1 regulatory override
        Set<ChannelType> channels = result.getRecipientChannels().get("trader_vip");
        assertThat(channels).containsExactlyInAnyOrder(ChannelType.EMAIL, ChannelType.SMS);

        // Verify that REGULATORY_OVERRIDE_APPLIED was recorded in AuditLog
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.REGULATORY_OVERRIDE_APPLIED
                && log.getMetadataReason().contains("FINRA/SEC duty-of-care override applied"));
    }

    @Test
    @Transactional
    @DisplayName("Tier 2: Non-critical SMS alert during quiet hours diverts to EMAIL with QUIET_HOURS_FALLBACK audit")
    void testTier2_QuietHoursDiversion_NonCriticalDivertsSmsToEmail() {
        Notification notification = Notification.builder()
                .sourceSystem("portfolio-service")
                .eventId("evt-rebalance-" + UUID.randomUUID())
                .idempotencyKey("idem-quiet-" + UUID.randomUUID())
                .notificationType("PORTFOLIO_UPDATE")
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .subject("Portfolio Rebalancing Complete")
                .body("Asset classes realigned according to target model.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("investor_quiet")
                .destination("+14155558900")
                .preferredChannels("SMS") // Requested SMS
                .quietHoursStart(22)
                .quietHoursEnd(7)
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Evaluate at 03:00 (3 AM, inside quiet hours)
        RoutingResult result = routingService.routeNotification(saved, 3);

        // SMS must be diverted to EMAIL
        Set<ChannelType> channels = result.getRecipientChannels().get("investor_quiet");
        assertThat(channels).containsExactly(ChannelType.EMAIL);
        assertThat(channels).doesNotContain(ChannelType.SMS);

        // Verify QUIET_HOURS_FALLBACK audit log
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.QUIET_HOURS_FALLBACK
                && log.getMetadataReason().contains("Diverted intrusive SMS to EMAIL"));
    }

    @Test
    @Transactional
    @DisplayName("Tier 3: Non-critical alert requested via opted-out SMS falls back to EMAIL with CHANNEL_FALLBACK_APPLIED audit")
    void testTier3_UserOptOutFallback_NonCriticalSmsDivertsToEmail() {
        Notification notification = Notification.builder()
                .sourceSystem("account-alerts")
                .eventId("evt-statement-" + UUID.randomUUID())
                .idempotencyKey("idem-optout-" + UUID.randomUUID())
                .notificationType("STATEMENT_READY")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .status(NotificationStatus.ACCEPTED)
                .subject("Monthly E-Statement Available")
                .body("Your September account statement is ready for download.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("investor_optout")
                .destination("+14155551234")
                .preferredChannels("SMS") // Requested SMS
                .optedOutChannels("SMS") // But explicitly opted out of SMS
                .quietHoursStart(22)
                .quietHoursEnd(7)
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Evaluate at 14:00 (2 PM, daytime outside quiet hours)
        RoutingResult result = routingService.routeNotification(saved, 14);

        // Opted out SMS must fallback to EMAIL
        Set<ChannelType> channels = result.getRecipientChannels().get("investor_optout");
        assertThat(channels).containsExactly(ChannelType.EMAIL);
        assertThat(channels).doesNotContain(ChannelType.SMS);

        // Verify CHANNEL_FALLBACK_APPLIED audit log
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.CHANNEL_FALLBACK_APPLIED
                && log.getMetadataReason().contains("User opted out of requested channel 'SMS'"));
    }

    @Test
    @Transactional
    @DisplayName("Standard Routing: Non-critical SMS outside quiet hours without opt-out routes cleanly to SMS")
    void testStandardRouting_DaytimeWithoutOptOut_DeliversSms() {
        Notification notification = Notification.builder()
                .sourceSystem("trading-service")
                .eventId("evt-daytime-" + UUID.randomUUID())
                .idempotencyKey("idem-daytime-" + UUID.randomUUID())
                .notificationType("TRADE_FILLED")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .subject("Trade Execution Alert")
                .body("Sold 25 shares of MSFT.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("trader_daytime")
                .destination("+14155554321")
                .preferredChannels("SMS")
                .optedOutChannels(null)
                .quietHoursStart(22)
                .quietHoursEnd(7)
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Evaluate at 15:00 (3 PM)
        RoutingResult result = routingService.routeNotification(saved, 15);

        Set<ChannelType> channels = result.getRecipientChannels().get("trader_daytime");
        assertThat(channels).containsExactly(ChannelType.SMS);

        // No fallbacks should have occurred
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(auditLogs).noneMatch(log -> log.getAction() == AuditAction.QUIET_HOURS_FALLBACK);
        assertThat(auditLogs).noneMatch(log -> log.getAction() == AuditAction.CHANNEL_FALLBACK_APPLIED);
        assertThat(auditLogs).noneMatch(log -> log.getAction() == AuditAction.REGULATORY_OVERRIDE_APPLIED);
        assertThat(auditLogs).anyMatch(log -> log.getAction() == AuditAction.ROUTED);
    }
}

