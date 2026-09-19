package com.demo.notification.channel;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.exception.PermanentProviderException;
import com.demo.notification.exception.TransientProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Channel provider implementation delivering notifications via Email using AWS Simple Email Service (SES).
 * <p>
 * Simulates realistic downstream HTTP responses including rate limits (429), service unavailability (503),
 * connection timeouts (408), and syntax rejections (400) for testing fault tolerance and resilience.
 */
@Component
@Slf4j
public class EmailChannelProvider implements ChannelProvider {

    private static final String PROVIDER_NAME = "AWS_SES";

    @Override
    public ChannelType getChannelType() {
        return ChannelType.EMAIL;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public DeliveryResponse send(Notification notification, NotificationRecipient recipient) {
        long startTime = System.currentTimeMillis();
        String destination = recipient.getDestination();

        log.info("[{}] Dispatching email to '{}' for notificationId='{}', subject='{}'",
                PROVIDER_NAME, destination, notification.getNotificationId(), notification.getSubject());

        // Simulate transient failures
        if (destination != null && destination.contains("transient-429")) {
            throw new TransientProviderException("Rate limit exceeded by AWS_SES (HTTP 429)", 429,
                    ErrorCategory.RATE_LIMIT_EXCEEDED, PROVIDER_NAME);
        }
        if (destination != null && destination.contains("transient-503")) {
            throw new TransientProviderException("AWS_SES service temporarily unavailable (HTTP 503)", 503,
                    ErrorCategory.TRANSIENT_PROVIDER_FAILURE, PROVIDER_NAME);
        }
        if (destination != null && destination.contains("transient-timeout")) {
            throw new TransientProviderException("Connection timeout to AWS_SES", 408,
                    ErrorCategory.TIMEOUT, PROVIDER_NAME);
        }

        // Simulate permanent failures
        if (destination == null || !destination.contains("@") || destination.contains("permanent-400")) {
            throw new PermanentProviderException("Invalid recipient email syntax (HTTP 400)", 400,
                    ErrorCategory.INVALID_RECIPIENT, PROVIDER_NAME);
        }
        if (destination.contains("permanent-401") || destination.contains("auth-401")) {
            throw new PermanentProviderException("AWS_SES authentication credentials expired (HTTP 401)", 401,
                    ErrorCategory.AUTH_ERROR, PROVIDER_NAME);
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("[{}] Email delivered successfully to '{}' in {}ms", PROVIDER_NAME, destination, executionTime);

        return DeliveryResponse.builder()
                .successful(true)
                .status(DeliveryStatus.SENT)
                .providerResponseCode("250_OK")
                .executionTimeMs(executionTime)
                .build();
    }
}
