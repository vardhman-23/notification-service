package com.demo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.RecipientRequestDto;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIngestionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("POST /api/v1/notifications for a new request returns HTTP 202 Accepted and records initial AuditLog")
    void testNewNotificationSubmission_Returns202Accepted() throws Exception {
        String uniqueIdempotencyKey = "idem-" + UUID.randomUUID();
        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("trading-platform")
                .eventId("trade-evt-" + UUID.randomUUID())
                .idempotencyKey(uniqueIdempotencyKey)
                .notificationType("TRADE_CONFIRMATION")
                .severity(Severity.MEDIUM)
                .priority(Priority.NORMAL)
                .subject("Trade Executed #12345")
                .body("Your order to buy 100 shares of SCHW was executed at $72.50")
                .recipients(List.of(
                        RecipientRequestDto.builder()
                                .recipientId("user_101")
                                .destination("trader@example.com")
                                .preferredChannels("EMAIL,SMS")
                                .build()
                ))
                .build();

        String responseBody = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sourceSystem").value("trading-platform"))
                .andExpect(jsonPath("$.recipients[0].destination").value("trader@example.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID notificationId = UUID.fromString(objectMapper.readTree(responseBody).get("notificationId").asText());

        // Verify database persistence
        assertThat(notificationRepository.findById(notificationId)).isPresent();

        // Verify initial AuditLog entry
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(notificationId);
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).getAction()).isEqualTo(AuditAction.ACCEPTED);
        assertThat(auditLogs.get(0).getMetadataReason()).contains("trading-platform");
    }

    @Test
    @DisplayName("POST /api/v1/notifications with identical idempotencyKey returns HTTP 200 OK and existing record")
    void testDuplicateSubmissionWithSameIdempotencyKey_Returns200Ok() throws Exception {
        String uniqueIdempotencyKey = "idem-duplicate-test-" + UUID.randomUUID();
        String eventId = "trade-evt-dup-" + UUID.randomUUID();

        NotificationRequestDto request = NotificationRequestDto.builder()
                .sourceSystem("portfolio-rebalancer")
                .eventId(eventId)
                .idempotencyKey(uniqueIdempotencyKey)
                .notificationType("REBALANCE_ALERT")
                .severity(Severity.LOW)
                .priority(Priority.LOW)
                .subject("Portfolio Rebalanced")
                .body("Your portfolio allocations have been updated.")
                .recipients(List.of(
                        RecipientRequestDto.builder()
                                .recipientId("user_202")
                                .destination("investor@example.com")
                                .preferredChannels("EMAIL")
                                .build()
                ))
                .build();

        // 1st submission: should return 202 Accepted
        String firstResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID firstNotificationId = UUID.fromString(objectMapper.readTree(firstResponse).get("notificationId").asText());

        // 2nd submission with identical idempotencyKey: must return HTTP 200 OK
        String secondResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(firstNotificationId.toString()))
                .andExpect(jsonPath("$.idempotencyKey").value(uniqueIdempotencyKey))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID secondNotificationId = UUID.fromString(objectMapper.readTree(secondResponse).get("notificationId").asText());
        assertThat(secondNotificationId).isEqualTo(firstNotificationId);

        // Verify duplicate audit history entry was logged
        List<AuditLog> auditLogs = auditLogRepository.findByNotificationIdOrderByTimestampAsc(firstNotificationId);
        assertThat(auditLogs).hasSize(2);
        assertThat(auditLogs.get(0).getAction()).isEqualTo(AuditAction.ACCEPTED);
        assertThat(auditLogs.get(1).getAction()).isEqualTo(AuditAction.SUPPRESSED_DUPLICATE);
    }

    @Test
    @DisplayName("POST /api/v1/notifications with invalid DTO returns HTTP 400 Bad Request with details")
    void testValidationFailures_Returns400BadRequest() throws Exception {
        NotificationRequestDto invalidRequest = NotificationRequestDto.builder()
                .sourceSystem("") // blank
                .eventId("") // blank
                .idempotencyKey("") // blank
                .notificationType(null)
                .severity(null)
                .priority(null)
                .body("") // blank
                .recipients(Collections.emptyList()) // empty
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isNotEmpty());
    }
}

