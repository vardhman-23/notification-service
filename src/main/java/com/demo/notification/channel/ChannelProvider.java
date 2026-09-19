package com.demo.notification.channel;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.types.ChannelType;

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

