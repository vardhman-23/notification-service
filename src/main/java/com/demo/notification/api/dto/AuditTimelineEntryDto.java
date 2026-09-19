package com.demo.notification.api.dto;

import com.demo.notification.domain.types.AuditAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit log record detailing state transitions, policy overrides, or suppression events.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Chronological audit timeline record")
public class AuditTimelineEntryDto {

    @Schema(description = "Unique audit record ID", example = "5fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Action or event type executed", example = "REGULATORY_OVERRIDE_APPLIED")
    private AuditAction action;

    @Schema(description = "Business context or policy rationale for the action",
            example = "FINRA/SEC regulatory override applied for CRITICAL alert enforcing mandatory SMS+EMAIL.")
    private String metadataReason;

    @Schema(description = "Sanitized, PII-free summary of the message payload", example = "Recipient: user_42, ForcedChannels: [EMAIL, SMS]")
    private String sanitizedPayloadSummary;

    @Schema(description = "UTC timestamp when the action occurred")
    private Instant timestamp;
}
