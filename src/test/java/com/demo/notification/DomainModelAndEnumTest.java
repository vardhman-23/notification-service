package com.demo.notification;

import com.demo.notification.api.error.ApiErrorResponse;
import com.demo.notification.api.dto.AuditTimelineEntryDto;
import com.demo.notification.api.dto.DeliveryAttemptResponseDto;
import com.demo.notification.api.dto.DeliveryProgressDto;
import com.demo.notification.api.dto.DeliverySummaryDto;
import com.demo.notification.api.dto.NotificationRequestDto;
import com.demo.notification.api.dto.NotificationResponseDto;
import com.demo.notification.api.dto.NotificationStatusResponseDto;
import com.demo.notification.api.dto.RecipientRequestDto;
import com.demo.notification.api.dto.RecipientResponseDto;
import com.demo.notification.channel.DeliveryResponse;
import com.demo.notification.config.OpenApiConfig;
import com.demo.notification.delivery.NotificationAcceptedEvent;
import com.demo.notification.delivery.NotificationRoutedEvent;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.IdempotencyRecord;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.AuditEventType;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.domain.types.Priority;
import com.demo.notification.domain.types.Severity;
import com.demo.notification.exception.NotificationNotFoundException;
import com.demo.notification.exception.PermanentProviderException;
import com.demo.notification.exception.ResourceNotFoundException;
import com.demo.notification.exception.TransientProviderException;
import com.demo.notification.service.IngestionResult;
import com.demo.notification.service.RoutingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelAndEnumTest {

    @Test
    @DisplayName("Enums: values and valueOf coverage")
    void testEnums() {
        for (NotificationStatus s : NotificationStatus.values()) {
            assertThat(NotificationStatus.valueOf(s.name())).isEqualTo(s);
        }
        for (Priority p : Priority.values()) {
            assertThat(Priority.valueOf(p.name())).isEqualTo(p);
        }
        for (Severity s : Severity.values()) {
            assertThat(Severity.valueOf(s.name())).isEqualTo(s);
        }
        for (ChannelType c : ChannelType.values()) {
            assertThat(ChannelType.valueOf(c.name())).isEqualTo(c);
        }
        for (DeliveryStatus d : DeliveryStatus.values()) {
            assertThat(DeliveryStatus.valueOf(d.name())).isEqualTo(d);
        }
        for (AuditAction a : AuditAction.values()) {
            assertThat(AuditAction.valueOf(a.name())).isEqualTo(a);
        }
        for (AuditEventType a : AuditEventType.values()) {
            assertThat(AuditEventType.valueOf(a.name())).isEqualTo(a);
        }
        for (ErrorCategory e : ErrorCategory.values()) {
            assertThat(ErrorCategory.valueOf(e.name())).isEqualTo(e);
        }
    }

    @Test
    @DisplayName("Notification Entity: prePersist, preUpdate, addRecipient, addDeliveryAttempt")
    void testNotificationEntityLifecycle() {
        Notification n = new Notification();
        n.prePersist();
        assertThat(n.getNotificationId()).isNotNull();
        assertThat(n.getCreatedAt()).isNotNull();
        assertThat(n.getUpdatedAt()).isNotNull();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.ACCEPTED);

        Instant initialUpdate = n.getUpdatedAt();
        n.preUpdate();
        assertThat(n.getUpdatedAt()).isNotNull();

        NotificationRecipient r = new NotificationRecipient();
        n.addRecipient(r);
        assertThat(n.getRecipients()).contains(r);
        assertThat(r.getNotification()).isEqualTo(n);

        DeliveryAttempt da = new DeliveryAttempt();
        n.addDeliveryAttempt(da);
        assertThat(n.getDeliveryAttempts()).contains(da);
        assertThat(da.getNotification()).isEqualTo(n);

        // PrePersist when values are already set
        UUID customId = UUID.randomUUID();
        Instant customTime = Instant.now().minusSeconds(100);
        Notification populated = Notification.builder()
                .notificationId(customId)
                .createdAt(customTime)
                .updatedAt(customTime)
                .status(NotificationStatus.DELIVERED)
                .build();
        populated.prePersist();
        assertThat(populated.getNotificationId()).isEqualTo(customId);
        assertThat(populated.getCreatedAt()).isEqualTo(customTime);
        assertThat(populated.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    @DisplayName("NotificationRecipient: prePersist and quiet hours permutations")
    void testNotificationRecipient() {
        NotificationRecipient r = new NotificationRecipient();
        r.prePersist();
        assertThat(r.getId()).isNotNull();
        assertThat(r.getCreatedAt()).isNotNull();

        r.setOptedOutChannels(null);
        assertThat(r.isChannelOptedOut(ChannelType.SMS)).isFalse();
        r.setOptedOutChannels("   ");
        assertThat(r.isChannelOptedOut(ChannelType.SMS)).isFalse();
        r.setOptedOutChannels("EMAIL, SMS");
        assertThat(r.isChannelOptedOut(ChannelType.SMS)).isTrue();
        assertThat(r.isChannelOptedOut(ChannelType.SLACK)).isFalse();

        // Quiet hours: null values
        r.setQuietHoursStart(null);
        r.setQuietHoursEnd(null);
        assertThat(r.isInQuietHours(2)).isFalse();

        r.setQuietHoursStart(22);
        r.setQuietHoursEnd(null);
        assertThat(r.isInQuietHours(2)).isFalse();

        // Midnight-spanning: 22 to 7
        r.setQuietHoursStart(22);
        r.setQuietHoursEnd(7);
        assertThat(r.isInQuietHours(23)).isTrue();
        assertThat(r.isInQuietHours(4)).isTrue();
        assertThat(r.isInQuietHours(7)).isFalse();
        assertThat(r.isInQuietHours(12)).isFalse();

        // Same-day: 1 to 5
        r.setQuietHoursStart(1);
        r.setQuietHoursEnd(5);
        assertThat(r.isInQuietHours(2)).isTrue();
        assertThat(r.isInQuietHours(0)).isFalse();
        assertThat(r.isInQuietHours(5)).isFalse();

        // Equal: 4 to 4
        r.setQuietHoursStart(4);
        r.setQuietHoursEnd(4);
        assertThat(r.isInQuietHours(4)).isFalse();
    }

    @Test
    @DisplayName("DeliveryAttempt: prePersist and preUpdate")
    void testDeliveryAttempt() {
        DeliveryAttempt da = new DeliveryAttempt();
        da.prePersist();
        assertThat(da.getId()).isNotNull();
        assertThat(da.getCreatedAt()).isNotNull();
        assertThat(da.getUpdatedAt()).isNotNull();

        da.preUpdate();
        assertThat(da.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("AuditLog and IdempotencyRecord: prePersist")
    void testAuditLogAndIdempotencyRecord() {
        AuditLog al = new AuditLog();
        al.prePersist();
        assertThat(al.getId()).isNotNull();
        assertThat(al.getTimestamp()).isNotNull();

        IdempotencyRecord ir = new IdempotencyRecord();
        ir.prePersist();
        assertThat(ir.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Domain Events: NotificationAcceptedEvent and NotificationRoutedEvent")
    void testDomainEvents() {
        UUID id = UUID.randomUUID();
        NotificationAcceptedEvent e1 = new NotificationAcceptedEvent(id);
        assertThat(e1.getNotificationId()).isEqualTo(id);
        assertThat(e1.getCorrelationId()).isNull();

        NotificationAcceptedEvent e2 = new NotificationAcceptedEvent(id, "trace-1");
        assertThat(e2.getNotificationId()).isEqualTo(id);
        assertThat(e2.getCorrelationId()).isEqualTo("trace-1");

        NotificationRoutedEvent r1 = new NotificationRoutedEvent(id);
        assertThat(r1.getNotificationId()).isEqualTo(id);
        assertThat(r1.getCorrelationId()).isNull();

        NotificationRoutedEvent r2 = new NotificationRoutedEvent(id, "trace-2");
        assertThat(r2.getNotificationId()).isEqualTo(id);
        assertThat(r2.getCorrelationId()).isEqualTo("trace-2");
    }

    @Test
    @DisplayName("Service Value Objects: IngestionResult and RoutingResult")
    void testServiceValueObjects() {
        NotificationResponseDto dto = NotificationResponseDto.builder().build();
        IngestionResult ir = new IngestionResult(dto, true);
        assertThat(ir.isDuplicate()).isTrue();
        assertThat(ir.getNotification()).isEqualTo(dto);

        Notification n = Notification.builder().build();
        Map<String, Set<ChannelType>> map = Map.of("u1", Set.of(ChannelType.EMAIL));
        RoutingResult rr = new RoutingResult(n, map);
        assertThat(rr.getNotification()).isEqualTo(n);
        assertThat(rr.getRecipientChannels()).isEqualTo(map);
    }

    @Test
    @DisplayName("DeliveryResponse: builder and properties")
    void testDeliveryResponse() {
        DeliveryResponse resp = DeliveryResponse.builder()
                .successful(true)
                .status(DeliveryStatus.SENT)
                .providerResponseCode("200_OK")
                .executionTimeMs(25L)
                .errorMessage(null)
                .errorCategory(null)
                .build();

        assertThat(resp.isSuccessful()).isTrue();
        assertThat(resp.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(resp.getProviderResponseCode()).isEqualTo("200_OK");
        assertThat(resp.getExecutionTimeMs()).isEqualTo(25L);
    }

    @Test
    @DisplayName("Exceptions: constructors and getters")
    void testExceptions() {
        TransientProviderException tpe = new TransientProviderException(
                "Rate limit", 429, ErrorCategory.RATE_LIMIT_EXCEEDED, "AWS_SES");
        assertThat(tpe.getMessage()).isEqualTo("Rate limit");
        assertThat(tpe.getStatusCode()).isEqualTo(429);
        assertThat(tpe.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT_EXCEEDED);
        assertThat(tpe.getProviderName()).isEqualTo("AWS_SES");

        PermanentProviderException ppe = new PermanentProviderException(
                "Bad syntax", 400, ErrorCategory.INVALID_RECIPIENT, "TWILIO");
        assertThat(ppe.getMessage()).isEqualTo("Bad syntax");
        assertThat(ppe.getStatusCode()).isEqualTo(400);
        assertThat(ppe.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_RECIPIENT);
        assertThat(ppe.getProviderName()).isEqualTo("TWILIO");

        ResourceNotFoundException rnfe = new ResourceNotFoundException("Not found");
        assertThat(rnfe.getMessage()).isEqualTo("Not found");

        UUID id = UUID.randomUUID();
        NotificationNotFoundException nnfe = new NotificationNotFoundException(id);
        assertThat(nnfe.getMessage()).contains(id.toString());

        NotificationNotFoundException nnfeMsg = new NotificationNotFoundException("Custom not found message");
        assertThat(nnfeMsg.getMessage()).isEqualTo("Custom not found message");
    }

    @Test
    @DisplayName("DTOs: constructors, getters, and setters")
    void testDtos() {
        NotificationRequestDto req = new NotificationRequestDto();
        req.setSourceSystem("sys");
        req.setEventId("evt");
        req.setIdempotencyKey("idem");
        req.setNotificationType("TYPE");
        req.setSeverity(Severity.CRITICAL);
        req.setPriority(Priority.URGENT);
        req.setSubject("Sub");
        req.setBody("Body");
        req.setRecipients(new ArrayList<>());
        req.setScheduledAt(Instant.now());
        req.setExpiresAt(Instant.now().plusSeconds(60));

        assertThat(req.getSourceSystem()).isEqualTo("sys");
        assertThat(req.getEventId()).isEqualTo("evt");
        assertThat(req.getIdempotencyKey()).isEqualTo("idem");
        assertThat(req.getNotificationType()).isEqualTo("TYPE");
        assertThat(req.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(req.getPriority()).isEqualTo(Priority.URGENT);
        assertThat(req.getSubject()).isEqualTo("Sub");
        assertThat(req.getBody()).isEqualTo("Body");
        assertThat(req.getRecipients()).isEmpty();
        assertThat(req.getScheduledAt()).isNotNull();
        assertThat(req.getExpiresAt()).isNotNull();

        RecipientRequestDto rreq = new RecipientRequestDto();
        rreq.setRecipientId("r1");
        rreq.setDestination("d1");
        rreq.setPreferredChannels("EMAIL");
        rreq.setOptedOutChannels("SMS");
        rreq.setQuietHoursStart(22);
        rreq.setQuietHoursEnd(7);

        assertThat(rreq.getRecipientId()).isEqualTo("r1");
        assertThat(rreq.getDestination()).isEqualTo("d1");
        assertThat(rreq.getPreferredChannels()).isEqualTo("EMAIL");
        assertThat(rreq.getOptedOutChannels()).isEqualTo("SMS");
        assertThat(rreq.getQuietHoursStart()).isEqualTo(22);
        assertThat(rreq.getQuietHoursEnd()).isEqualTo(7);

        RecipientResponseDto rresp = new RecipientResponseDto();
        UUID rId = UUID.randomUUID();
        rresp.setId(rId);
        rresp.setRecipientId("r1");
        rresp.setDestination("d1");
        rresp.setPreferredChannels("EMAIL");
        rresp.setOptedOutChannels("SMS");
        rresp.setQuietHoursStart(22);
        rresp.setQuietHoursEnd(7);
        rresp.setCreatedAt(Instant.now());

        assertThat(rresp.getId()).isEqualTo(rId);
        assertThat(rresp.getRecipientId()).isEqualTo("r1");
        assertThat(rresp.getDestination()).isEqualTo("d1");

        DeliveryAttemptResponseDto daResp = new DeliveryAttemptResponseDto();
        daResp.setId(UUID.randomUUID());
        daResp.setRecipientId("r1");
        daResp.setChannel(ChannelType.EMAIL);
        daResp.setAttemptNumber(1);
        daResp.setStatus(DeliveryStatus.SENT);
        daResp.setProvider("AWS_SES");
        daResp.setProviderResponseCode("250");
        daResp.setErrorMessage("err");
        daResp.setExecutionTime(10L);
        daResp.setErrorCategory(ErrorCategory.TIMEOUT);
        daResp.setCreatedAt(Instant.now());

        assertThat(daResp.getChannel()).isEqualTo(ChannelType.EMAIL);
        assertThat(daResp.getAttemptNumber()).isEqualTo(1);
        assertThat(daResp.getStatus()).isEqualTo(DeliveryStatus.SENT);

        DeliverySummaryDto sum = new DeliverySummaryDto();
        sum.setTotalChannels(2);
        sum.setTotalAttempts(3);
        sum.setSuccessfulCount(1);
        sum.setFailedCount(1);
        sum.setPendingCount(0);
        sum.setRetryingCount(1);

        assertThat(sum.getTotalChannels()).isEqualTo(2);
        assertThat(sum.getTotalAttempts()).isEqualTo(3);
        assertThat(sum.getSuccessfulCount()).isEqualTo(1);
        assertThat(sum.getFailedCount()).isEqualTo(1);
        assertThat(sum.getPendingCount()).isZero();
        assertThat(sum.getRetryingCount()).isEqualTo(1);

        DeliveryProgressDto prog = new DeliveryProgressDto();
        prog.setAttemptId(UUID.randomUUID());
        prog.setRecipientId("r1");
        prog.setChannel(ChannelType.SMS);
        prog.setProvider("TWILIO");
        prog.setStatus(DeliveryStatus.SENT);
        prog.setAttemptNumber(1);
        prog.setProviderResponseCode("200");
        prog.setErrorMessage(null);
        prog.setExecutionTimeMs(50L);
        prog.setErrorCategory(null);
        prog.setSentAt(Instant.now());
        prog.setCreatedAt(Instant.now());

        assertThat(prog.getChannel()).isEqualTo(ChannelType.SMS);
        assertThat(prog.getProvider()).isEqualTo("TWILIO");

        AuditTimelineEntryDto auditDto = new AuditTimelineEntryDto();
        auditDto.setId(UUID.randomUUID());
        auditDto.setAction(AuditAction.ACCEPTED);
        auditDto.setMetadataReason("Accepted");
        auditDto.setSanitizedPayloadSummary("Summary");
        auditDto.setTimestamp(Instant.now());

        assertThat(auditDto.getAction()).isEqualTo(AuditAction.ACCEPTED);
        assertThat(auditDto.getMetadataReason()).isEqualTo("Accepted");

        ApiErrorResponse err = new ApiErrorResponse();
        err.setStatus(400);
        err.setError("Bad Request");
        err.setMessage("Invalid input");
        err.setPath("/api/v1/notifications");
        err.setCorrelationId("trace-123");
        err.setDetails(List.of("error detail"));
        err.setTimestamp(Instant.now());

        assertThat(err.getStatus()).isEqualTo(400);
        assertThat(err.getCorrelationId()).isEqualTo("trace-123");
        assertThat(err.getPath()).isEqualTo("/api/v1/notifications");
    }

    @Test
    @DisplayName("OpenApiConfig initialization")
    void testOpenApiConfig() {
        OpenApiConfig config = new OpenApiConfig();
        assertThat(config).isNotNull();
    }
}

