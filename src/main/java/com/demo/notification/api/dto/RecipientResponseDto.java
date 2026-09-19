package com.demo.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted recipient response model reflecting preferences, opt-outs, and quiet hours.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Persisted recipient profile and channel preferences")
public class RecipientResponseDto {

    @Schema(description = "Database record ID", example = "4fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Unique recipient ID", example = "client_trader_01")
    private String recipientId;

    @Schema(description = "Destination address", example = "trader@example.com")
    private String destination;

    @Schema(description = "User preferred channels", example = "EMAIL,SMS")
    private String preferredChannels;

    @Schema(description = "User opted-out channels", example = "SMS")
    private String optedOutChannels;

    @Schema(description = "Quiet-hours start hour in 24h UTC", example = "22")
    private Integer quietHoursStart;

    @Schema(description = "Quiet-hours end hour in 24h UTC", example = "7")
    private Integer quietHoursEnd;

    @Schema(description = "Record creation timestamp in UTC")
    private Instant createdAt;
}
