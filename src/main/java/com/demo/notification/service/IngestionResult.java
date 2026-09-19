package com.demo.notification.service;

import com.demo.notification.api.dto.NotificationResponseDto;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class IngestionResult {
    private final NotificationResponseDto notification;
    private final boolean duplicate;
}

