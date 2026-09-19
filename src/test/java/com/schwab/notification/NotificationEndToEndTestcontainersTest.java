package com.schwab.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.notification.api.dto.NotificationRequestDto;
import com.schwab.notification.api.dto.NotificationResponseDto;
import com.schwab.notification.api.dto.NotificationStatusResponseDto;
import com.schwab.notification.api.dto.RecipientRequestDto;
import com.schwab.notification.delivery.DeliveryWorker;
import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.DeliveryAttempt;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.DeliveryAttemptRepository;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.domain.types.ErrorCategory;
import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.domain.types.Priority;
import com.schwab.notification.domain.types.Severity;
import com.schwab.notification.service.RoutingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test suite using Testcontainers (PostgreSQL 16).
 *
 * Covers all Charles Schwab Stage 4 Prompt 4.2 scenarios:
 * 1. Duplicate submission idempotency.
 * 2. Transient error triggering bounded retries.
 * 3. Permanent error terminating without retries.
 * 4. Audit trail completeness across the lifecycle.
 *
 * Configured with conditional execution so that environments without Docker daemon
 * (e.g. lightweight CI or local dev without Docker Desktop) gracefully skip,
 * while container-capable CI/CD environments execute against real PostgreSQL 16.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisabledIf("isDockerUnavailable")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationEndToEndTestcontainersTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("schwab_notification_test")
            .withUsername("schwab_user")
            .withPassword("schwab_pass");

    static boolean isDockerUnavailable() {
        try {
            return !DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return true;
        }
    }

    @DynamicPropertySource
    static void configurePostgresProperties(DynamicPropertyRegistry registry) {
        if (postgres != null && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.flyway.enabled", () -> "true");
        }
    }

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
    @DisplayName("Testcontainers E2E - Scenario 1: Duplicate submission idempotency against PostgreSQL")
    void testDuplicateSubmissionIdempotency_Postgres() throws Exception {
        String uniqueIdempotencyKey = "pg-e2e-idem-" + UUID.randomUUID();
        String eventId = "evt-pg-trade-" + UUID.randomUUID();

        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("trading-platform")
                .eventId(eventId)
                .idempotencyKey(uniqueIdempotencyKey)
                .notificationType("ORDER_SUBMISSION")
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .subject("Postgres E2E: Order Placed")
                .body("Market order executed via PostgreSQL Testcontainers.")
                .recipients(List.of(
                        RecipientRequestDto.builder()
                                .recipientId("user_pg_1")
                                .destination("trader1@schwab.com")
                                .preferredChannels("EMAIL")
                                .build()
                ))
                .build();

        // 1st submission -> HTTP 202 ACCEPTED
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

        // 2nd duplicate submission -> HTTP 200 OK with same ID
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

        // Verify exactly one entity persisted
        assertThat(notificationRepository.findByEventId(eventId)).hasSize(1);

        // Verify audit log has ACCEPTED and SUPPRESSED_DUPLICATE
        List<AuditLog> logs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(notificationId);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getAction()).isEqualTo(AuditAction.ACCEPTED);
        assertThat(logs.get(1).getAction()).isEqualTo(AuditAction.SUPPRESSED_DUPLICATE);
    }

    @Test
    @DisplayName("Testcontainers E2E - Scenario 2: Transient error (HTTP 429) triggers bounded retries in PostgreSQL")
    void testTransientError_TriggersBoundedRetries_Postgres() {
        Notification notification = Notification.builder()
                .sourceSystem("rate-test-service")
                .eventId("evt-pg-transient-" + UUID.randomUUID())
                .idempotencyKey("idem-pg-transient-" + UUID.randomUUID())
                .notificationType("RATE_THROTTLE_ALERT")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .subject("Postgres E2E: Throttle Event")
                .body("Testing rate limit retries in PostgreSQL.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("throttled_client_pg")
                .destination("transient-429@test.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.FAILED);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByNotificationNotificationId(saved.getNotificationId());
        assertThat(attempts).hasSize(1);
        DeliveryAttempt attempt = attempts.get(0);

        // Bounded retry should have reached attempt 3
        assertThat(attempt.getAttemptNumber()).isGreaterThanOrEqualTo(3);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT_EXCEEDED);

        List<AuditLog> logs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.FAILED && l.getMetadataReason().contains("exhausting retries"));
    }

    @Test
    @DisplayName("Testcontainers E2E - Scenario 3: Permanent error (HTTP 400) terminates without retries in PostgreSQL")
    void testPermanentError_TerminatesWithoutRetries_Postgres() {
        Notification notification = Notification.builder()
                .sourceSystem("account-service")
                .eventId("evt-pg-perm-" + UUID.randomUUID())
                .idempotencyKey("idem-pg-perm-" + UUID.randomUUID())
                .notificationType("SECURITY_NOTICE")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .status(NotificationStatus.ACCEPTED)
                .subject("Postgres E2E: Security Notice")
                .body("Testing permanent error termination in PostgreSQL.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("client_bad_pg")
                .destination("permanent-400-bad-syntax")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        Notification updated = notificationRepository.findById(saved.getNotificationId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.FAILED);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByNotificationNotificationId(saved.getNotificationId());
        assertThat(attempts).hasSize(1);
        DeliveryAttempt attempt = attempts.get(0);

        // Must terminate immediately on attempt 1 without retry
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(attempt.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_RECIPIENT);
        assertThat(attempt.getProviderResponseCode()).isEqualTo("400");

        List<AuditLog> logs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(logs).noneMatch(l -> l.getAction() == AuditAction.RETRY_SCHEDULED);
        assertThat(logs).anyMatch(l -> l.getAction() == AuditAction.FAILED && l.getMetadataReason().contains("Permanent provider rejection"));
    }

    @Test
    @DisplayName("Testcontainers E2E - Scenario 4: Audit trail completeness across lifecycle in PostgreSQL")
    void testAuditTrailCompletenessAcrossLifecycle_Postgres() throws Exception {
        Notification notification = Notification.builder()
                .sourceSystem("wealth-management")
                .eventId("evt-pg-lifecycle-" + UUID.randomUUID())
                .idempotencyKey("idem-pg-lifecycle-" + UUID.randomUUID())
                .notificationType("PORTFOLIO_REBALANCE")
                .severity(Severity.LOW)
                .priority(Priority.NORMAL)
                .status(NotificationStatus.ACCEPTED)
                .subject("Portfolio Rebalanced")
                .body("Model portfolio asset allocation rebalanced.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("pg_wealth_client")
                .destination("investor@schwab.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Record ACCEPTED
        auditLogRepository.save(AuditLog.builder()
                .notificationId(saved.getNotificationId())
                .action(AuditAction.ACCEPTED)
                .metadataReason("Accepted from wealth-management")
                .sanitizedPayloadSummary("Type: PORTFOLIO_REBALANCE, Priority: NORMAL")
                .build());

        // Transition through ROUTED
        routingService.routeNotification(saved);

        // Transition through DELIVERED
        deliveryWorker.processDelivery(saved.getNotificationId());

        // Verify GET /api/v1/notifications/{id}
        String json = mockMvc.perform(get("/api/v1/notifications/{id}", saved.getNotificationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregateStatus").value("DELIVERED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        NotificationStatusResponseDto response = objectMapper.readValue(json, NotificationStatusResponseDto.class);
        assertThat(response.getAggregateStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(response.getDeliveryProgress()).hasSize(1);
        assertThat(response.getDeliveryProgress().get(0).getStatus()).isEqualTo(DeliveryStatus.SENT);

        // Verify complete audit timeline
        List<AuditLog> fullTimeline = auditLogRepository.findByNotificationIdOrderByTimestampAsc(saved.getNotificationId());
        assertThat(fullTimeline).extracting(AuditLog::getAction)
                .containsSubsequence(AuditAction.ACCEPTED, AuditAction.ROUTED, AuditAction.DELIVERED);

        for (AuditLog entry : fullTimeline) {
            assertThat(entry.getTimestamp()).isNotNull();
            assertThat(entry.getAction()).isNotNull();
            assertThat(entry.getMetadataReason()).isNotBlank();
            assertThat(entry.getSanitizedPayloadSummary()).isNotBlank();
            assertThat(entry.getMetadataReason()).doesNotContain("password", "token", "secret");
        }
    }
}

