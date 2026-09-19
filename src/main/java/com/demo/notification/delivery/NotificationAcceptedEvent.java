package com.demo.notification.delivery;

import lombok.Getter;

import java.util.UUID;

/**
 * Domain event published when a notification has been accepted and persisted.
 * <p>
 * Carries the {@code notificationId} and optional {@code correlationId} across the
 * transaction boundary to trigger asynchronous routing and delivery pipeline with
 * continuous distributed tracing context.
 */
@Getter
public class NotificationAcceptedEvent {

    private final UUID notificationId;
    private final String correlationId;

    public NotificationAcceptedEvent(UUID notificationId) {
        this(notificationId, null);
    }

    public NotificationAcceptedEvent(UUID notificationId, String correlationId) {
        this.notificationId = notificationId;
        this.correlationId = correlationId;
    }
}
