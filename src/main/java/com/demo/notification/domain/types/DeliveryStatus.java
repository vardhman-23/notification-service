package com.demo.notification.domain.types;

/**
 * Status of an individual delivery attempt for a recipient on a channel.
 */
public enum DeliveryStatus {
    PENDING,
    SENT,
    RETRYING,
    FAILED,
    CANCELLED
}

