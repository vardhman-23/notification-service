package com.schwab.notification.domain.types;

/**
 * Event types recorded in the immutable audit history.
 */
public enum AuditEventType {
    NOTIFICATION_ACCEPTED,
    NOTIFICATION_REJECTED,
    NOTIFICATION_SUPPRESSED_DUPLICATE,
    ROUTING_COMPLETED,
    DELIVERY_QUEUED,
    DELIVERY_ATTEMPTED,
    DELIVERY_SUCCEEDED,
    DELIVERY_FAILED,
    RETRY_SCHEDULED
}

