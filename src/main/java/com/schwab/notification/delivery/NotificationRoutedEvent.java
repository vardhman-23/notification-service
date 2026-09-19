package com.schwab.notification.delivery;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class NotificationRoutedEvent {
    private final UUID notificationId;
}

