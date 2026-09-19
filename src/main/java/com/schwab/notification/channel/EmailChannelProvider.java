package com.schwab.notification.channel;

import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.types.ChannelType;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.domain.types.ErrorCategory;
import com.schwab.notification.exception.PermanentProviderException;
import com.schwab.notification.exception.TransientProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
            throw new PermanentProviderException("Authentication / authorization rejected by AWS_SES (HTTP 401)", 401,
                    ErrorCategory.AUTH_ERROR, PROVIDER_NAME);
        }

        long executionTime = System.currentTimeMillis() - startTime;
        return DeliveryResponse.builder()
                .successful(true)
                .status(DeliveryStatus.SENT)
                .providerResponseCode("250_OK")
                .executionTimeMs(executionTime)
                .build();
    }
}
