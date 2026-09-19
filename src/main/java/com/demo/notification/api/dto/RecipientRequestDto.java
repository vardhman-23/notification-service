package com.demo.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Recipient specification including destination address, preferences, opt-outs, and quiet-hour windows.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Recipient delivery details and channel preferences")
public class RecipientRequestDto {

    @NotBlank(message = "Recipient ID is required")
    @Schema(description = "Unique user or account identifier for the recipient", example = "client_trader_01")
    private String recipientId;

    @NotBlank(message = "Destination address (email/phone/webhook) is required")
    @Schema(description = "Destination address such as email or E.164 phone number", example = "trader@example.com")
    private String destination;

    @Schema(description = "Comma-delimited preferred channels (e.g. EMAIL, SMS)", example = "EMAIL,SMS")
    private String preferredChannels;

    @Schema(description = "Comma-delimited channels the user has explicitly opted out of", example = "SMS")
    private String optedOutChannels;

    @Schema(description = "Quiet-hours start hour in 24h UTC (0-23)", example = "22")
    private Integer quietHoursStart;

    @Schema(description = "Quiet-hours end hour in 24h UTC (0-23)", example = "7")
    private Integer quietHoursEnd;
}
