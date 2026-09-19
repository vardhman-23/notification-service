package com.schwab.notification.domain.types;

/**
 * Classification of delivery errors to drive bounded retry and circuit-breaking decisions.
 */
public enum ErrorCategory {
    TRANSIENT_PROVIDER_FAILURE(true),
    RATE_LIMIT_EXCEEDED(true),
    TIMEOUT(true),
    PERMANENT_PROVIDER_REJECTION(false),
    INVALID_RECIPIENT(false),
    AUTH_ERROR(false);

    private final boolean retryable;

    ErrorCategory(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

