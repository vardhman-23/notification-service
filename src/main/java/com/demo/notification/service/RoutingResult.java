package com.demo.notification.service;

import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.types.ChannelType;
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

