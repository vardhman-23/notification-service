package com.schwab.notification.service;

import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.types.ChannelType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;
import java.util.Set;

@Getter
@AllArgsConstructor
public class RoutingResult {
    private final Notification notification;
    private final Map<String, Set<ChannelType>> recipientChannels;
}

