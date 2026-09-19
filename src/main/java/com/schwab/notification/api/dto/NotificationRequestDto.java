package com.schwab.notification.api.dto;

import com.schwab.notification.domain.types.Priority;
import com.schwab.notification.domain.types.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRequestDto {

    @NotBlank(message = "Source system identifier is required")
    private String sourceSystem;

    @NotBlank(message = "Event identifier is required")
    private String eventId;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    @NotBlank(message = "Notification type is required")
    private String notificationType;

    @NotNull(message = "Severity is required")
    private Severity severity;

    @NotNull(message = "Priority is required")
    private Priority priority;

    @Size(max = 255, message = "Subject must not exceed 255 characters")
    private String subject;

    @NotBlank(message = "Notification body is required")
    private String body;

    @NotEmpty(message = "At least one recipient must be specified")
    @Valid
    private List<RecipientRequestDto> recipients;

    private Instant scheduledAt;

    private Instant expiresAt;
}

