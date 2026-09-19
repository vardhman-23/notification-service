package com.demo.notification.delivery;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.channel.ChannelProvider;
import com.demo.notification.channel.ChannelProviderRegistry;
import com.demo.notification.channel.DeliveryResponse;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.DeliveryAttemptRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.exception.PermanentProviderException;
import com.demo.notification.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.demo.notification.observability.NotificationMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryWorkerUnitTest {

    private NotificationRepository notificationRepository;
    private DeliveryAttemptRepository deliveryAttemptRepository;
    private AuditLogRepository auditLogRepository;
    private ChannelProviderRegistry channelProviderRegistry;
    private ProviderDispatchService providerDispatchService;
    private NotificationMetrics notificationMetrics;
    private ChannelProvider emailProvider;
    private DeliveryWorker deliveryWorker;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        deliveryAttemptRepository = mock(DeliveryAttemptRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        providerDispatchService = mock(ProviderDispatchService.class);
        notificationMetrics = mock(NotificationMetrics.class);

        emailProvider = mock(ChannelProvider.class);
        when(emailProvider.getChannelType()).thenReturn(ChannelType.EMAIL);
        when(emailProvider.getProviderName()).thenReturn("AWS_SES");
        when(emailProvider.isAvailable()).thenReturn(true);

        channelProviderRegistry = new ChannelProviderRegistry(List.of(emailProvider));

        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        deliveryWorker = new DeliveryWorker(
                notificationRepository,
                deliveryAttemptRepository,
                auditLogRepository,
                channelProviderRegistry,
                providerDispatchService,
                notificationMetrics
        );
    }

    @Test
    @DisplayName("ProcessDelivery: throws ResourceNotFoundException when notification does not exist")
    void testNotificationNotFound() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deliveryWorker.processDelivery(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("ProcessDelivery: recipient missing from notification marks attempt FAILED")
    void testRecipientMissing() {
        UUID id = UUID.randomUUID();
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .recipientId("missing_user")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.PENDING)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .recipients(new ArrayList<>())
                .deliveryAttempts(new ArrayList<>(List.of(attempt)))
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        deliveryWorker.processDelivery(id);

        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorMessage()).contains("Recipient metadata missing");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        verify(deliveryAttemptRepository).save(attempt);
    }

    @Test
    @DisplayName("ProcessDelivery: provider missing from registry marks attempt FAILED")
    void testProviderMissing() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("user_1")
                .destination("user@example.com")
                .build();
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .recipientId("user_1")
                .channel(ChannelType.SLACK)
                .status(DeliveryStatus.PENDING)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>(List.of(attempt)))
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        deliveryWorker.processDelivery(id);

        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorMessage()).contains("No provider available");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        verify(deliveryAttemptRepository).save(attempt);
    }

    @Test
    @DisplayName("ProcessDelivery: permanent exception marks attempt FAILED and logs audit")
    void testPermanentException() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("user_1")
                .destination("invalid-syntax")
                .build();
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .recipientId("user_1")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.PENDING)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>(List.of(attempt)))
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(providerDispatchService.dispatch(eq(emailProvider), eq(notification), eq(recipient), eq(attempt)))
                .thenThrow(new PermanentProviderException("Syntax error", 400, ErrorCategory.INVALID_RECIPIENT, "AWS_SES"));

        deliveryWorker.processDelivery(id);

        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_RECIPIENT);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        verify(auditLogRepository, org.mockito.Mockito.times(2)).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("ProcessDelivery: unexpected generic exception marks attempt FAILED")
    void testUnexpectedException() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("user_1")
                .destination("user@example.com")
                .build();
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .recipientId("user_1")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.PENDING)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .recipients(new ArrayList<>(List.of(recipient)))
                .deliveryAttempts(new ArrayList<>(List.of(attempt)))
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(providerDispatchService.dispatch(eq(emailProvider), eq(notification), eq(recipient), eq(attempt)))
                .thenThrow(new RuntimeException("I/O failure"));

        deliveryWorker.processDelivery(id);

        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorMessage()).contains("Unexpected failure");
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
    }

    @Test
    @DisplayName("ProcessDelivery: partial delivery (1 success, 1 failure) sets aggregate status DELIVERED")
    void testPartialDelivery() {
        UUID id = UUID.randomUUID();
        NotificationRecipient recipient1 = NotificationRecipient.builder()
                .recipientId("user_1")
                .destination("ok@example.com")
                .build();
        NotificationRecipient recipient2 = NotificationRecipient.builder()
                .recipientId("user_2")
                .destination("bad@example.com")
                .build();

        DeliveryAttempt attempt1 = DeliveryAttempt.builder()
                .recipientId("user_1")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.PENDING)
                .build();
        DeliveryAttempt attempt2 = DeliveryAttempt.builder()
                .recipientId("user_2")
                .channel(ChannelType.EMAIL)
                .status(DeliveryStatus.PENDING)
                .build();

        Notification notification = Notification.builder()
                .notificationId(id)
                .recipients(new ArrayList<>(List.of(recipient1, recipient2)))
                .deliveryAttempts(new ArrayList<>(List.of(attempt1, attempt2)))
                .build();

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        when(providerDispatchService.dispatch(eq(emailProvider), eq(notification), eq(recipient1), eq(attempt1)))
                .thenReturn(DeliveryResponse.builder().successful(true).status(DeliveryStatus.SENT).build());

        when(providerDispatchService.dispatch(eq(emailProvider), eq(notification), eq(recipient2), eq(attempt2)))
                .thenReturn(DeliveryResponse.builder().successful(false).status(DeliveryStatus.FAILED).build());

        deliveryWorker.processDelivery(id);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    @DisplayName("OnNotificationRouted: restores correlationId into MDC and invokes processDelivery")
    void testOnNotificationRoutedWithCorrelationId() {
        UUID id = UUID.randomUUID();
        NotificationRoutedEvent event = new NotificationRoutedEvent(id, "test-routed-correlation-id");

        Notification notification = Notification.builder()
                .notificationId(id)
                .recipients(new ArrayList<>())
                .deliveryAttempts(new ArrayList<>())
                .build();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        deliveryWorker.onNotificationRouted(event);

        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }
}

