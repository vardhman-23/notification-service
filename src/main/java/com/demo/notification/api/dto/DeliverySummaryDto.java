package com.demo.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * High-level delivery metrics summarizing channel counts and attempt statuses.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Aggregated delivery summary metrics")
public class DeliverySummaryDto {

    @Schema(description = "Total number of channels assigned across all recipients", example = "2")
    private int totalChannels;

    @Schema(description = "Sum total of delivery attempts executed", example = "2")
    private int totalAttempts;

    @Schema(description = "Number of successfully delivered channels", example = "2")
    private int successfulCount;

    @Schema(description = "Number of failed channels", example = "0")
    private int failedCount;

    @Schema(description = "Number of channels awaiting delivery dispatch", example = "0")
    private int pendingCount;

    @Schema(description = "Number of channels currently scheduled for retry", example = "0")
    private int retryingCount;
}
