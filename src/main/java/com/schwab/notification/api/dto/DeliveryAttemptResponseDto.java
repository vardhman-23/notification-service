package com.schwab.notification.api.dto;

import com.schwab.notification.domain.types.ChannelType;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.domain.types.ErrorCategory;
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
public class DeliveryAttemptResponseDto {
    private UUID id;
    private String recipientId;
    private ChannelType channel;
    private int attemptNumber;
    private DeliveryStatus status;
    private String provider;
    private String providerResponseCode;
    private String errorMessage;
    private Long executionTime;
    private ErrorCategory errorCategory;
    private Instant createdAt;
}

