package com.schwab.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.notification.api.dto.NotificationStatusResponseDto;
import com.schwab.notification.delivery.DeliveryWorker;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.ChannelType;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.domain.types.Priority;
import com.schwab.notification.domain.types.Severity;
import com.schwab.notification.service.RoutingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationQueryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RoutingService routingService;

    @Autowired
    private DeliveryWorker deliveryWorker;

    @Test
    @DisplayName("GET /api/v1/notifications/{id} returns aggregate status, channel progress, and audit timeline")
    void testQueryNotificationStatus_ReturnsAggregateStatusAndTimeline() throws Exception {
        // Setup notification, route, and deliver
        Notification notification = Notification.builder()
                .sourceSystem("trading-platform")
                .eventId("evt-trade-query-" + UUID.randomUUID())
                .idempotencyKey("idem-query-" + UUID.randomUUID())
                .notificationType("EXECUTION_FILL")
                .severity(Severity.HIGH)
                .priority(Priority.HIGH)
                .status(NotificationStatus.ACCEPTED)
                .subject("Order Filled: 50 AAPL @ $220.00")
                .body("Order #99281 executed successfully.")
                .build();

        NotificationRecipient recipient = NotificationRecipient.builder()
                .recipientId("trader_query_user")
                .destination("trader_query@schwab.com")
                .preferredChannels("EMAIL")
                .build();

        notification.addRecipient(recipient);
        Notification saved = notificationRepository.save(notification);

        // Transition through lifecycle: Route -> Deliver
        routingService.routeNotification(saved);
        deliveryWorker.processDelivery(saved.getNotificationId());

        // Query the API
        String jsonResponse = mockMvc.perform(get("/api/v1/notifications/{id}", saved.getNotificationId())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(saved.getNotificationId().toString()))
                .andExpect(jsonPath("$.aggregateStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.sourceSystem").value("trading-platform"))
                .andExpect(jsonPath("$.deliverySummary.successfulCount").value(1))
                .andExpect(jsonPath("$.deliverySummary.failedCount").value(0))
                .andExpect(jsonPath("$.deliveryProgress").isArray())
                .andExpect(jsonPath("$.deliveryProgress[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.deliveryProgress[0].status").value("SENT"))
                .andExpect(jsonPath("$.deliveryProgress[0].provider").value("AWS_SES"))
                .andExpect(jsonPath("$.auditTimeline").isArray())
                .andReturn()
                .getResponse()
                .getContentAsString();

        NotificationStatusResponseDto response = objectMapper.readValue(jsonResponse, NotificationStatusResponseDto.class);

        assertThat(response.getNotificationId()).isEqualTo(saved.getNotificationId());
        assertThat(response.getAggregateStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(response.getDeliveryProgress()).hasSize(1);
        assertThat(response.getDeliveryProgress().get(0).getChannel()).isEqualTo(ChannelType.EMAIL);
        assertThat(response.getDeliveryProgress().get(0).getStatus()).isEqualTo(DeliveryStatus.SENT);

        // Verify chronological timeline contains the routing and delivery events
        assertThat(response.getAuditTimeline()).isNotEmpty();
        assertThat(response.getAuditTimeline()).extracting(entry -> entry.getAction())
                .contains(AuditAction.ROUTED, AuditAction.DELIVERED);
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id} for non-existent ID returns HTTP 404 Not Found")
    void testQueryNonExistentNotification_Returns404NotFound() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/notifications/{id}", nonExistentId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Notification not found with ID: " + nonExistentId));
    }
}

