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

class EmailChannelProviderTest {

    private EmailChannelProvider provider;
    private Notification notification;

    @BeforeEach
    void setUp() {
        provider = new EmailChannelProvider();
        notification = Notification.builder()
                .notificationId(UUID.randomUUID())
                .subject("Test Email Subject")
                .body("Test message body")
                .build();
    }

    @Test
    @DisplayName("Metadata: channel type, provider name, and availability")
    void testMetadata() {
        assertThat(provider.getChannelType()).isEqualTo(ChannelType.EMAIL);
        assertThat(provider.getProviderName()).isEqualTo("AWS_SES");
        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("Send: Successful delivery")
    void testSendSuccess() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("trader@example.com")
                .build();

        DeliveryResponse response = provider.send(notification, recipient);

        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(response.getProviderResponseCode()).isEqualTo("250_OK");
    }

    @Test
    @DisplayName("Send: Transient 429 rate limit exception")
    void testSendTransient429() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("transient-429@example.com")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipient))
                .isInstanceOf(TransientProviderException.class)
                .satisfies(ex -> {
                    TransientProviderException tpe = (TransientProviderException) ex;
                    assertThat(tpe.getStatusCode()).isEqualTo(429);
                    assertThat(tpe.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT_EXCEEDED);
                    assertThat(tpe.getProviderName()).isEqualTo("AWS_SES");
                });
    }

    @Test
    @DisplayName("Send: Transient 503 service unavailable exception")
    void testSendTransient503() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("transient-503@example.com")
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
                .destination("transient-timeout@example.com")
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

        NotificationRecipient recipientNoAt = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("invalid-syntax-no-at-sign")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipientNoAt))
                .isInstanceOf(PermanentProviderException.class);

        NotificationRecipient recipientNamed = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("permanent-400@example.com")
                .build();

        assertThatThrownBy(() -> provider.send(notification, recipientNamed))
                .isInstanceOf(PermanentProviderException.class);
    }

    @Test
    @DisplayName("Send: Permanent 401 auth exception")
    void testSendPermanent401() {
        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("u1")
                .destination("permanent-401@example.com")
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
                .destination("auth-401@example.com")
                .build();

        assertThatThrownBy(() -> provider.send(notification, authRecipient))
                .isInstanceOf(PermanentProviderException.class);
    }
}

