package com.schwab.notification.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientResponseDto {
    private UUID id;
    private String recipientId;
    private String destination;
    private String preferredChannels;
    private String optedOutChannels;
    private Integer quietHoursStart;
    private Integer quietHoursEnd;
    private Instant createdAt;
}

