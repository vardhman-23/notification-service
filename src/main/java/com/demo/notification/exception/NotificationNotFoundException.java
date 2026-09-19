package com.demo.notification.exception;

import java.util.UUID;

/**
 * Exception thrown when a requested notification aggregate cannot be found.
 */
public class NotificationNotFoundException extends ResourceNotFoundException {

    public NotificationNotFoundException(UUID notificationId) {
        super("Notification not found with ID: " + notificationId);
    }

    public NotificationNotFoundException(String message) {
        super(message);
    }
}

