package com.schwab.notification.channel;

import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.types.ChannelType;

/**
 * Strategy interface for delivery channel providers.
 */
public interface ChannelProvider {

    /**
     * The type of channel this provider implements (EMAIL, SMS, WEBHOOK, etc.)
     */
    ChannelType getChannelType();

    /**
     * Unique identifier for the provider (e.g. AWS_SES, TWILIO).
     */
    String getProviderName();

    /**
     * Indicates whether this provider is currently available and healthy.
     */
    boolean isAvailable();

    /**
     * Sends the notification to the target recipient.
     */
    DeliveryResponse send(Notification notification, NotificationRecipient recipient);
}

