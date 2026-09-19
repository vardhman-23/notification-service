package com.demo.notification.domain.types;

/**
 * Lifecycle status of a notification request as specified in the Enterprise domain model.
 */
public enum NotificationStatus {
    ACCEPTED,
    ROUTED,
    QUEUED,
    DELIVERING,
    DELIVERED,
    FAILED,
    RETRY_SCHEDULED,
    DEAD_LETTER
}
