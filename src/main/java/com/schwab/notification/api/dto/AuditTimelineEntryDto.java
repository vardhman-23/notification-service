package com.schwab.notification.api.dto;

import com.schwab.notification.domain.types.AuditAction;
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
public class AuditTimelineEntryDto {
    private UUID id;
    private AuditAction action;
    private String metadataReason;
    private String sanitizedPayloadSummary;
    private Instant timestamp;
}

