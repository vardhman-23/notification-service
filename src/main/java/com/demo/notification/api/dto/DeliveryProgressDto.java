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
 * Detailed status view for an individual delivery attempt across a specific channel and provider.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Individual channel delivery attempt details")
public class DeliveryProgressDto {

    @Schema(description = "Unique identifier of the delivery attempt", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID attemptId;

    @Schema(description = "Target recipient identifier", example = "client_trader_01")
    private String recipientId;

    @Schema(description = "Delivery channel type", example = "EMAIL")
    private ChannelType channel;

    @Schema(description = "Downstream provider handling dispatch", example = "AWS_SES")
    private String provider;

    @Schema(description = "Attempt delivery status", example = "SENT")
    private DeliveryStatus status;

    @Schema(description = "Attempt number (1-based)", example = "1")
    private int attemptNumber;

    @Schema(description = "Raw response code returned by the provider", example = "250_OK")
    private String providerResponseCode;

    @Schema(description = "Error diagnostic message if attempt failed", example = "null")
    private String errorMessage;

    @Schema(description = "Execution latency in milliseconds", example = "45")
    private Long executionTimeMs;

    @Schema(description = "Classified fault category if failed", example = "TRANSIENT_PROVIDER_FAILURE")
    private ErrorCategory errorCategory;

    @Schema(description = "Timestamp when provider accepted the message")
    private Instant sentAt;

    @Schema(description = "Timestamp when attempt record was created")
    private Instant createdAt;
}
