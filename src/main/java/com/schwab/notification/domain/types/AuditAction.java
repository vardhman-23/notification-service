package com.schwab.notification.domain.types;

/**
 * Action recorded in the audit trail.
 */
public enum AuditAction {
    ACCEPTED,
    ROUTED,
    QUEUED,
    DELIVERING,
    DELIVERED,
    FAILED,
    RETRY_SCHEDULED,
    SUPPRESSED_DUPLICATE,
    REGULATORY_OVERRIDE_APPLIED,
    QUIET_HOURS_FALLBACK,
    CHANNEL_FALLBACK_APPLIED
}
