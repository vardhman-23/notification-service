package com.schwab.notification.domain.types;

/**
 * Delivery channels supported by the notification system.
 */
public enum ChannelType {
    EMAIL,
    SMS,
    WEBHOOK,
    SLACK,
    IN_APP
}
