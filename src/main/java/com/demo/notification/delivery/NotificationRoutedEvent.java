package com.demo.notification.delivery;

import lombok.Getter;

import java.util.UUID;

/**
 * Domain event published when channel routing and policy evaluation (ADR-001) has completed.
 * <p>
 * Signals the delivery layer to initiate provider dispatch with end-to-end tracing context.
 */
@Getter
public class NotificationRoutedEvent {

    private final UUID notificationId;
    private final String correlationId;

    public NotificationRoutedEvent(UUID notificationId) {
        this(notificationId, null);
    }

    public NotificationRoutedEvent(UUID notificationId, String correlationId) {
        this.notificationId = notificationId;
        this.correlationId = correlationId;
    }
}
