package com.schwab.notification.api.dto;

import jakarta.validation.constraints.NotBlank;
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
public class RecipientRequestDto {

    @NotBlank(message = "Recipient ID is required")
    private String recipientId;

    @NotBlank(message = "Destination address (email/phone/webhook) is required")
    private String destination;

    private String preferredChannels;
    private String optedOutChannels;
    private Integer quietHoursStart;
    private Integer quietHoursEnd;
}

