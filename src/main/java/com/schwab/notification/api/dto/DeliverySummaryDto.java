package com.schwab.notification.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliverySummaryDto {
    private int totalChannels;
    private int totalAttempts;
    private int successfulCount;
    private int failedCount;
    private int pendingCount;
    private int retryingCount;
}

