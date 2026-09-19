package com.demo.notification.api.dto;

import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted delivery attempt representation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Persisted delivery attempt record")
public class DeliveryAttemptResponseDto {

    @Schema(description = "Attempt ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Recipient ID", example = "client_trader_01")
    private String recipientId;

    @Schema(description = "Channel type", example = "EMAIL")
    private ChannelType channel;

    @Schema(description = "Attempt count", example = "1")
    private int attemptNumber;

    @Schema(description = "Delivery status", example = "SENT")
    private DeliveryStatus status;

    @Schema(description = "Channel provider", example = "AWS_SES")
    private String provider;

    @Schema(description = "Provider response code", example = "250_OK")
    private String providerResponseCode;

    @Schema(description = "Diagnostic error message if failed")
    private String errorMessage;

    @Schema(description = "Latency in milliseconds", example = "45")
    private Long executionTime;

    @Schema(description = "Fault categorization")
    private ErrorCategory errorCategory;

    @Schema(description = "Creation timestamp in UTC")
    private Instant createdAt;
}
