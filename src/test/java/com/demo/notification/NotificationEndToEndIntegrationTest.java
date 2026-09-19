package com.demo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.NotificationResponseDto;
import com.demo.notification.api.dto.NotificationStatusResponseDto;
import com.demo.notification.api.dto.RecipientRequestDto;
import com.demo.notification.delivery.DeliveryWorker;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.DeliveryAttemptRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.service.RoutingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationEndToEndIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RoutingService routingService;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Test
    @DisplayName("Scenario 1: Duplicate submission idempotency prevents duplicate entities and returns existing record")
    void testDuplicateSubmissionIdempotency() throws Exception {
        String uniqueIdempotencyKey = "e2e-idem-" + UUID.randomUUID();
        String eventId = "evt-e2e-trade-" + UUID.randomUUID();

        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("trading-platform")
                .eventId(eventId)
                .idempotencyKey(uniqueIdempotencyKey)
                .notificationType("ORDER_SUBMISSION")
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .subject("Order Placed: 100 SCHW")
                .body("Market order placed successfully.")
                .recipients(List.of(
                        RecipientRequestDto.builder()
                                .recipientId("user_e2e_1")
                                .destination("trader1@example.com")
                                .preferredChannels("EMAIL")
                                .build()
                ))
                .build();

        // First submission -> 202 Accepted
        String resp1 = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        NotificationResponseDto dto1 = objectMapper.readValue(resp1, NotificationResponseDto.class);
        UUID notificationId = dto1.getNotificationId();

        // Second submission with exact same idempotencyKey -> 200 OK with same ID
        String resp2 = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(notificationId.toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        NotificationResponseDto dto2 = objectMapper.readValue(resp2, NotificationResponseDto.class);
        assertThat(dto2.getNotificationId()).isEqualTo(notificationId);

        // Verify only 1 logical notification entity exists
        assertThat(notificationRepository.findById(notificationId)).isPresent();
        assertThat(notificationRepository.findByEventId(eventId)).hasSize(1);

        // Verify AuditLog contains ACCEPTED followed by SUPPRESSED_DUPLICATE
        List<AuditLog> logs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(notificationId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getAction()).isEqualTo(AuditAction.ACCEPTED);
        assertThat(logs.get(1).getAction()).isEqualTo(AuditAction.SUPPRESSED_DUPLICATE);
    }

    @Test
    @DisplayName("Scenario 2: Transient error (HTTP 429) triggers bounded exponential retries and records RETRY_SCHEDULED")
    void testTransientError_TriggersBoundedRetries() {
        Notification notification = Notification.builder()
                .sourceSystem("rate-test-service")
                .eventId("evt-transient-" + UUID.randomUUID())
                .idempotencyKey("idem-transient-" + UUID.randomUUID())
                .notificationType("RATE_THROTTLE_ALERT")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .subject("High Frequency Event")
                .body("Downstream rate limits simulated.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("throttled_client")
                .destination("transient-429@test.com") // Destination simulates HTTP 429
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Route and dispatch
        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByNotificationNotificationId(saved.getNotificationId());
        assertThat(attempts).hasSize(1);
        DeliveryAttempt attempt = attempts.get(0);

        // Bounded retry should have executed 3 attempts
        assertThat(attempt.getAttemptNumber()).isGreaterThanOrEqualTo(3);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT_EXCEEDED);

        // Verify AuditLog recorded RETRY_SCHEDULED across attempts and final ROUTED_TO_DEAD_LETTER
        List<AuditLog> logs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.ROUTED_TO_DEAD_LETTER);
    }

    @Test
    @DisplayName("Scenario 3: Permanent error (HTTP 400 invalid recipient) terminates immediately on attempt 1 without retries")
    void testPermanentError_TerminatesWithoutRetries() {
        Notification notification = Notification.builder()
                .sourceSystem("account-service")
                .eventId("evt-perm-" + UUID.randomUUID())
                .idempotencyKey("idem-perm-" + UUID.randomUUID())
                .notificationType("SECURITY_NOTICE")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .status(NotificationStatus.ACCEPTED)
                .subject("Security Confirmation")
                .body("Password reset completed.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_bad_syntax")
                .destination("permanent-400-bad-syntax") // Simulates HTTP 400 rejection
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByNotificationNotificationId(saved.getNotificationId());
        assertThat(attempts).hasSize(1);
        DeliveryAttempt attempt = attempts.get(0);

        // Must terminate strictly on attempt 1 with zero retries
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_RECIPIENT);
        assertThat(attempt.getProviderResponseCode()).isEqualTo("400");

        // Verify AuditLog recorded FAILED without any RETRY_SCHEDULED entries and routed to dead letter
        List<AuditLog> logs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(logs).noneMatch(l -> l.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.FAILED && l.getMetadataReason().contains("Permanent provider rejection"));
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.ROUTED_TO_DEAD_LETTER);
    }

    @Test
    @DisplayName("Scenario 4: Audit trail completeness across full notification lifecycle")
    void testAuditTrailCompletenessAcrossLifecycle() throws Exception {
        Notification notification = Notification.builder()
                .sourceSystem("wealth-management")
                .eventId("evt-lifecycle-" + UUID.randomUUID())
                .idempotencyKey("idem-lifecycle-" + UUID.randomUUID())
                .notificationType("DIVIDEND_REINVESTMENT")
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .subject("Quarterly Dividend Reinvested")
                .body("Dividend of $142.50 reinvested in SCHD.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("dividend_client")
                .destination("dividend@example.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Log initial ACCEPTED audit
        AuditLog acceptedLog = AuditLog.builder()
                .notificationId(saved.getNotificationId())
                .action(AuditAction.ACCEPTED)
                .metadataReason("Accepted from wealth-management")
                .sanitizedPayloadSummary("Type: DIVIDEND_REINVESTMENT, Priority: NORMAL")
                .build();
        auditLogRepository.save(acceptedLog);

        // Transition through ROUTED
        routingService.routeNotification(saved);

        // Transition through DELIVERED
        deliveryWorker.processDelivery(saved.getNotificationId());

        // Query the complete status & timeline API
        String json = mockMvc.perform(get("/api/v1/notifications/{id}", saved.getNotificationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateStatus").value("DELIVERED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        NotificationStatusResponseDto response = objectMapper.readValue(json, NotificationStatusResponseDto.class);

        // Verify aggregate and channel progress
        assertThat(response.getAggregateStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(response.getDeliveryProgress()).hasSize(1);
        assertThat(response.getDeliveryProgress().get(0).getStatus()).isEqualTo(DeliveryStatus.SENT);

        // Verify audit timeline integrity: chronological, non-null, and complete
        List<AuditLog> fullTimeline = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(fullTimeline).isNotEmpty();
        assertThat(fullTimeline).extracting(AuditLog::getAction)
                .containsSubsequence(AuditAction.ACCEPTED, AuditAction.ROUTED, AuditAction.DELIVERED);

        // Verify each audit record contains valid timestamps and sanitized details
        for (AuditLog entry : fullTimeline) {
            assertThat(entry.getTimestamp()).isNotNull();
            assertThat(entry.getAction()).isNotNull();
            assertThat(entry.getMetadataReason()).isNotBlank();
            assertThat(entry.getSanitizedPayloadSummary()).isNotBlank();
            // Verify PII or passwords are not exposed
            assertThat(entry.getMetadataReason()).doesNotContain("password", "token", "secret");
        }
    }

    @Test
    @DisplayName("Scenario 5: Explicit dispatch endpoint drives accepted notification through routing and delivery")
    void testManualDispatchEndpoint_DrivesNotificationToDelivered() throws Exception {
        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("settlement-service")
                .eventId("evt-settle-" + UUID.randomUUID())
                .idempotencyKey("idem-settle-" + UUID.randomUUID())
                .notificationType("TRADE_SETTLEMENT")
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .subject("Trade Settled: 50 SCHW")
                .body("Your trade has fully settled.")
                .recipients(List.of(
                        RecipientRequestDto.builder()
                                .recipientId("client_settle")
                                .destination("settle@example.com")
                                .preferredChannels("EMAIL")
                                .build()
                ))
                .build();

        // 1. Submit -> 202 Accepted
        String submitResp = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        NotificationResponseDto dto = objectMapper.readValue(submitResp, NotificationResponseDto.class);
        UUID notificationId = dto.getNotificationId();

        // 2. Trigger dispatch endpoint -> 200 OK with DELIVERED status
        mockMvc.perform(post("/api/v1/notifications/{id}/dispatch", notificationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.deliverySummary.successfulCount").value(1));

        // 3. Verify status query returns updated progress and complete timeline
        mockMvc.perform(get("/api/v1/notifications/{id}", notificationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveryProgress[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.deliveryProgress[0].status").value("SENT"))
                .andExpect(jsonPath("$.auditTimeline[0].action").value("ACCEPTED"))
                .andExpect(jsonPath("$.auditTimeline[1].action").value("ROUTED"))
                .andExpect(jsonPath("$.auditTimeline[2].action").value("DELIVERED"));
    }
}

