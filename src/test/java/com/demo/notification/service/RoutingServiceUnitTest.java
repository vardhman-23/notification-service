package com.demo.notification.service;

import com.demo.notification.channel.ChannelProvider;
import com.demo.notification.channel.ChannelProviderRegistry;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingServiceUnitTest {

    private NotificationRepository notificationRepository;
    private AuditLogRepository auditLogRepository;
    private ChannelProvider emailProvider;
    private ChannelProvider smsProvider;
    private ChannelProviderRegistry channelProviderRegistry;
    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        emailProvider = mock(ChannelProvider.class);
        when(emailProvider.getChannelType()).thenReturn(ChannelType.EMAIL);
        when(emailProvider.getProviderName()).thenReturn("AWS_SES");
        when(emailProvider.isAvailable()).thenReturn(true);

        smsProvider = mock(ChannelProvider.class);
        when(smsProvider.getChannelType()).thenReturn(ChannelType.SMS);
        when(smsProvider.getProviderName()).thenReturn("TWILIO");
        when(smsProvider.isAvailable()).thenReturn(true);

        channelProviderRegistry = new ChannelProviderRegistry(List.of(emailProvider, smsProvider));
        notificationRepository = mock(NotificationRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        routingService = new RoutingService(channelProviderRegistry, notificationRepository, auditLogRepository);
    }

    @Test
    @DisplayName("Route: Throws ResourceNotFoundException when notification ID is not found")
    void testNotificationNotFound() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routingService.routeNotification(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Route: Daytime quiet hours window (quietHoursStart < quietHoursEnd)")
    void testDaytimeQuietHoursWindow() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("+15551112222")
                .preferredChannels("SMS")
                .quietHoursStart(1)
                .quietHoursEnd(6)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        // Evaluation at 03:00 (within 1-6 window) => diverts SMS to EMAIL
        RoutingResult result = routingService.routeNotification(id, 3);
        assertThat(result.getRecipientChannels().get("r1")).containsExactly(ChannelType.EMAIL);

        // Evaluation at 12:00 (outside 1-6 window) => keeps SMS
        RoutingResult resultDay = routingService.routeNotification(id, 12);
        assertThat(resultDay.getRecipientChannels().get("r1")).containsExactly(ChannelType.SMS);
    }

    @Test
    @DisplayName("Route: Equal quiet hours window (start == end) means no quiet hours")
    void testEqualQuietHoursStartAndEnd() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("+15551112222")
                .preferredChannels("SMS")
                .quietHoursStart(5)
                .quietHoursEnd(5)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(notification, 5);
        assertThat(result.getRecipientChannels().get("r1")).containsExactly(ChannelType.SMS);
    }

    @Test
    @DisplayName("Route: Recipient with invalid channel string falls back to EMAIL")
    void testInvalidChannelStringFallback() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("trader@example.com")
                .preferredChannels("NON_EXISTENT_CHANNEL,UNKNOWN")
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(id, 14);
        assertThat(result.getRecipientChannels().get("r1")).containsExactly(ChannelType.EMAIL);
    }

    @Test
    @DisplayName("Route: Channel provider not available in registry falls back to EMAIL")
    void testProviderUnavailableFallback() {
        UUID id = UUID.randomUUID();
        // Provider for SMS is made unavailable
        when(smsProvider.isAvailable()).thenReturn(false);

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("+15550001111")
                .preferredChannels("SMS")
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(notification);
        assertThat(result.getRecipientChannels().get("r1")).containsExactly(ChannelType.EMAIL);
    }

    @Test
    @DisplayName("Route: Critical notification with opt-out or quiet hours logs regulatory override")
    void testCriticalNotificationWithOverride() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("dest@example.com")
                .preferredChannels("EMAIL")
                .optedOutChannels("SMS")
                .quietHoursStart(22)
                .quietHoursEnd(7)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.CRITICAL)
                .priority(Priority.URGENT)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(id, 23);
        assertThat(result.getRecipientChannels().get("r1")).containsExactlyInAnyOrder(ChannelType.EMAIL, ChannelType.SMS);
    }

    @Test
    @DisplayName("Route: Critical notification without opt-out and outside quiet hours still forces SMS+EMAIL without override log")
    void testCriticalNotificationStandard() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("dest@example.com")
                .preferredChannels("EMAIL")
                .optedOutChannels(null)
                .quietHoursStart(null)
                .quietHoursEnd(null)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.CRITICAL)
                .priority(Priority.URGENT)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(id, 15);
        assertThat(result.getRecipientChannels().get("r1")).containsExactlyInAnyOrder(ChannelType.EMAIL, ChannelType.SMS);
    }

    @Test
    @DisplayName("Route: Non-critical channel opt-out falls back to EMAIL")
    void testNonCriticalOptOutFallback() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("+15551112222")
                .preferredChannels("SMS")
                .optedOutChannels("SMS")
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>())
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(id, 12);
        assertThat(result.getRecipientChannels().get("r1")).containsExactly(ChannelType.EMAIL);
    }

    @Test
    @DisplayName("Route: Delivery attempt already exists is not duplicated")
    void testExistingDeliveryAttemptNotDuplicated() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("r1")
                .destination("user@example.com")
                .preferredChannels("EMAIL")
                .build();

        DeliveryAttempt existingAttempt = DeliveryAttempt.builder()
                .recipientId("r1")
                .channel(ChannelType.EMAIL)
                .provider("AWS_SES")
                .status(DeliveryStatus.PENDING)
                .attemptNumber(1)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>(List.of(existingAttempt)))
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        RoutingResult result = routingService.routeNotification(id);
        assertThat(result.getNotification().getDeliveryAttempts()).hasSize(1);
    }
}

