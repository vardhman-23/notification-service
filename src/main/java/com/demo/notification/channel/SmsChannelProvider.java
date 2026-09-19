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
 * Channel provider implementation delivering SMS notifications via Twilio.
 * <p>
 * Simulates transient 429/503/timeout conditions and permanent 400/401 rejections for resilience validation.
 */
@Component
@Slf4j
public class SmsChannelProvider implements ChannelProvider {

    private static final String PROVIDER_NAME = "TWILIO";

    @Override
    public ChannelType getChannelType() {
        return ChannelType.SMS;
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

        log.info("[{}] Dispatching SMS to '{}' for notificationId='{}'",
                PROVIDER_NAME, destination, notification.getNotificationId());

        if (destination != null && destination.contains("transient-429")) {
            throw new TransientProviderException("Twilio SMS rate limit exceeded (HTTP 429)", 429,
                    ErrorCategory.RATE_LIMIT_EXCEEDED, PROVIDER_NAME);
        }
        if (destination != null && destination.contains("transient-503")) {
            throw new TransientProviderException("Twilio service temporarily unavailable (HTTP 503)", 503,
                    ErrorCategory.TRANSIENT_PROVIDER_FAILURE, PROVIDER_NAME);
        }
        if (destination != null && destination.contains("transient-timeout")) {
            throw new TransientProviderException("Twilio request timed out", 408,
                    ErrorCategory.TIMEOUT, PROVIDER_NAME);
        }

        if (destination == null || destination.trim().isEmpty() || destination.contains("permanent-400")) {
            throw new PermanentProviderException("Invalid recipient phone number (HTTP 400)", 400,
                    ErrorCategory.INVALID_RECIPIENT, PROVIDER_NAME);
        }
        if (destination.contains("permanent-401") || destination.contains("auth-401")) {
            throw new PermanentProviderException("Twilio auth credentials expired / rejected (HTTP 401)", 401,
                    ErrorCategory.AUTH_ERROR, PROVIDER_NAME);
        }

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("[{}] SMS delivered successfully to '{}' in {}ms", PROVIDER_NAME, destination, executionTime);

        return DeliveryResponse.builder()
                .successful(true)
                .status(DeliveryStatus.SENT)
                .providerResponseCode("200_DELIVERED")
                .executionTimeMs(executionTime)
                .build();
    }
}
