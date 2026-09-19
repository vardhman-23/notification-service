package com.schwab.notification.delivery;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

/**
 * Domain event published when a notification has been accepted and persisted.
 * Triggers asynchronous routing and delivery pipeline if auto-dispatch is enabled.
 */
@Getter
@AllArgsConstructor
public class NotificationAcceptedEvent {
    private final UUID notificationId;
}

