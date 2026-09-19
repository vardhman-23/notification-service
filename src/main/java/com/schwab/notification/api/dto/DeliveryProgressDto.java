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
public class DeliveryProgressDto {
    private UUID attemptId;
    private String recipientId;
    private ChannelType channel;
    private String provider;
    private DeliveryStatus status;
    private int attemptNumber;
    private String providerResponseCode;
    private String errorMessage;
    private Long executionTimeMs;
    private ErrorCategory errorCategory;
    private Instant sentAt;
    private Instant createdAt;
}

