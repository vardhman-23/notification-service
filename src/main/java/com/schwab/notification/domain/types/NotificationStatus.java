package com.schwab.notification.domain.types;

/**
 * Lifecycle status of a notification request as specified in the Schwab domain model.
 */
public enum NotificationStatus {
    ACCEPTED,
    ROUTED,
    QUEUED,
    DELIVERING,
    DELIVERED,
    FAILED,
    RETRY_SCHEDULED
}
