package com.demo.notification.channel;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.exception.PermanentProviderException;
import com.demo.notification.exception.TransientProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsChannelProviderTest {

    private SmsChannelProvider provider;
    private Notification notification;

    @BeforeEach
    void setUp() {
        provider = new SmsChannelProvider();
        notification = Notification.builder()
                .notificationId(UUID.randomUUID())
                .subject("Test Alert")
                .body("Test message")
                .build();
    }

    @Test
    @DisplayName("Metadata: channel type, provider name, and availability")
    void testMetadata() {
        assertThat(provider.getChannelType()).isEqualTo(ChannelType.SMS);
        assertThat(provider.getProviderName()).isEqualTo("TWILIO");
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("Send: Successful delivery")
    void testSendSuccess() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+15551234567")
                .build();

        DeliveryResponse response = provider.send(notification, recipient);

        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(response.getProviderResponseCode()).isEqualTo("200_DELIVERED");
    }

    @Test
    @DisplayName("Send: Transient 429 rate limit exception")
    void testSendTransient429() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+1555-transient-429")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipient))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(ex -> {
                    TransientProviderException tpe = (TransientProviderException) ex;
                    assertThat(tpe.getStatusCode()).isEqualTo(429);
                    assertThat(tpe.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT_EXCEEDED);
                    assertThat(tpe.getProviderName()).isEqualTo("TWILIO");
                });
    }

    @Test
    @DisplayName("Send: Transient 503 service unavailable exception")
    void testSendTransient503() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+1555-transient-503")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipient))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(ex -> {
                    TransientProviderException tpe = (TransientProviderException) ex;
                    assertThat(tpe.getStatusCode()).isEqualTo(503);
                    assertThat(tpe.getErrorCategory()).isEqualTo(ErrorCategory.TRANSIENT_PROVIDER_FAILURE);
                });
    }

    @Test
    @DisplayName("Send: Transient timeout exception")
    void testSendTransientTimeout() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+1555-transient-timeout")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipient))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(ex -> {
                    TransientProviderException tpe = (TransientProviderException) ex;
                    assertThat(tpe.getStatusCode()).isEqualTo(408);
                    assertThat(tpe.getErrorCategory()).isEqualTo(ErrorCategory.TIMEOUT);
                });
    }

    @Test
    @DisplayName("Send: Permanent 400 invalid recipient exception")
    void testSendPermanent400() {
        NotificationRecipient recipientNull = NotificationRecipient.builder()
                .recipientId("u1")
                .destination(null)
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipientNull))
                .isInstanceOf(PermanentProviderException.class)
                .satisfies(ex -> {
                    PermanentProviderException ppe = (PermanentProviderException) ex;
                    assertThat(ppe.getStatusCode()).isEqualTo(400);
                    assertThat(ppe.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_RECIPIENT);
                });

        NotificationRecipient recipientEmpty = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("   ")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipientEmpty))
                .isInstanceOf(PermanentProviderException.class);

        NotificationRecipient recipientNamed = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+1555-permanent-400")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipientNamed))
                .isInstanceOf(PermanentProviderException.class);
    }

    @Test
    @DisplayName("Send: Permanent 401 auth exception")
    void testSendPermanent401() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+1555-permanent-401")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipient))
                .isInstanceOf(PermanentProviderException.class)
                .satisfies(ex -> {
                    PermanentProviderException ppe = (PermanentProviderException) ex;
                    assertThat(ppe.getStatusCode()).isEqualTo(401);
                    assertThat(ppe.getErrorCategory()).isEqualTo(ErrorCategory.AUTH_ERROR);
                });

        NotificationRecipient authRecipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("+1555-auth-401")
                .build();

        assertThatThrownBy(() -> provider.send(notification, authRecipient))
                .isInstanceOf(PermanentProviderException.class);
    }
}

