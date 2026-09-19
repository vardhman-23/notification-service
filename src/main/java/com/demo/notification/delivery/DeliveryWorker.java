package com.demo.notification.delivery;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.channel.ChannelProvider;
import com.demo.notification.channel.ChannelProviderRegistry;
import com.demo.notification.channel.DeliveryResponse;
import com.demo.notification.domain.model.AuditLog;
import com.demo.notification.domain.model.DeliveryAttempt;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.model.NotificationRecipient;
import com.demo.notification.domain.repository.AuditLogRepository;
import com.demo.notification.domain.repository.DeliveryAttemptRepository;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.AuditAction;
import com.demo.notification.domain.types.DeliveryStatus;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.exception.PermanentProviderException;
import com.demo.notification.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Worker component responsible for executing staged delivery attempts across configured channels.
 * <p>
 * Evaluates delivery responses, records individual attempt outcomes and tamper-evident audit logs,
 * handles permanent downstream rejections immediately, and computes final aggregate notification status.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryWorker {

    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final AuditLogRepository auditLogRepository;
    private final ChannelProviderRegistry channelProviderRegistry;
    private final ProviderDispatchService providerDispatchService;

    /**
     * Listens for {@link NotificationRoutedEvent} to trigger delivery asynchronously.
     *
     * @param event domain event containing notification ID and tracing context
     */
    @Async
    @EventListener
    public void onNotificationRouted(NotificationRoutedEvent event) {
        String correlationId = event.getCorrelationId();
        if (StringUtils.hasText(correlationId)) {
            MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, correlationId);
        }

        try {
            log.info("Received NotificationRoutedEvent for notificationId='{}'", event.getNotificationId());
            processDelivery(event.getNotificationId());
        } finally {
            if (StringUtils.hasText(correlationId)) {
                MDC.remove(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            }
        }
    }

    /**
     * Executes all staged delivery attempts for the given notification aggregate.
     *
     * @param notificationId unique identifier of the notification
     */
    @Transactional
    public void processDelivery(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));

        notification.setStatus(NotificationStatus.DELIVERING);
        notificationRepository.save(notification);

        List<DeliveryAttempt> attempts = notification.getDeliveryAttempts();
        log.info("Processing {} delivery attempts for notificationId='{}'", attempts.size(), notificationId);

        int successCount = 0;
        int failureCount = 0;

        for (DeliveryAttempt attempt : attempts) {
            NotificationRecipient recipient = notification.getRecipients().stream()
                    .filter(r -> r.getRecipientId().equals(attempt.getRecipientId()))
                    .findFirst()
                    .orElse(null);

            if (recipient == null) {
                log.error("Recipient '{}' not found in notification '{}'", attempt.getRecipientId(), notificationId);
                attempt.setStatus(DeliveryStatus.FAILED);
                attempt.setErrorMessage("Recipient metadata missing");
                deliveryAttemptRepository.save(attempt);
                failureCount++;
                continue;
            }

            ChannelProvider provider = channelProviderRegistry.getProvider(attempt.getChannel())
                    .orElse(null);

            if (provider == null) {
                log.error("No provider registered for channel '{}'", attempt.getChannel());
                attempt.setStatus(DeliveryStatus.FAILED);
                attempt.setErrorMessage("No provider available for channel " + attempt.getChannel());
                deliveryAttemptRepository.save(attempt);
                failureCount++;
                continue;
            }

            try {
                DeliveryResponse response = providerDispatchService.dispatch(provider, notification, recipient, attempt);

                if (response.isSuccessful()) {
                    attempt.setStatus(DeliveryStatus.SENT);
                    attempt.setSentAt(Instant.now());
                    deliveryAttemptRepository.save(attempt);
                    successCount++;

                    // Record DELIVERED in AuditLog
                    AuditLog successAudit = AuditLog.builder()
                            .notificationId(notificationId)
                            .action(AuditAction.DELIVERED)
                            .metadataReason(String.format("Delivered successfully to recipient '%s' via %s",
                                    recipient.getRecipientId(), attempt.getChannel()))
                            .sanitizedPayloadSummary(String.format("Channel: %s, ResponseCode: %s, TimeMs: %d",
                                    attempt.getChannel(), response.getProviderResponseCode(), response.getExecutionTimeMs()))
                            .timestamp(Instant.now())
                            .build();
                    auditLogRepository.save(successAudit);
                } else {
                    attempt.setStatus(DeliveryStatus.FAILED);
                    deliveryAttemptRepository.save(attempt);
                    failureCount++;
                }

            } catch (PermanentProviderException ex) {
                // Permanent failure: terminate immediately on attempt 1, mark FAILED, record in AuditLog
                log.error("Permanent failure encountered for recipient '{}' on channel '{}': {}",
                        recipient.getRecipientId(), attempt.getChannel(), ex.getMessage());

                attempt.setStatus(DeliveryStatus.FAILED);
                attempt.setErrorCategory(ex.getErrorCategory());
                attempt.setErrorMessage("Permanent rejection: " + ex.getMessage());
                attempt.setProviderResponseCode(String.valueOf(ex.getStatusCode()));
                deliveryAttemptRepository.save(attempt);
                failureCount++;

                AuditLog permanentFailAudit = AuditLog.builder()
                        .notificationId(notificationId)
                        .action(AuditAction.FAILED)
                        .metadataReason(String.format("Permanent provider rejection (HTTP %d) for recipient '%s' on %s: %s",
                                ex.getStatusCode(), recipient.getRecipientId(), attempt.getChannel(), ex.getMessage()))
                        .sanitizedPayloadSummary(String.format("Channel: %s, ErrorCategory: %s, StatusCode: %d",
                                attempt.getChannel(), ex.getErrorCategory(), ex.getStatusCode()))
                        .timestamp(Instant.now())
                        .build();
                auditLogRepository.save(permanentFailAudit);

            } catch (Exception ex) {
                log.error("Unexpected error delivering to recipient '{}': {}", recipient.getRecipientId(), ex.getMessage());
                attempt.setStatus(DeliveryStatus.FAILED);
                attempt.setErrorMessage("Unexpected failure: " + ex.getMessage());
                deliveryAttemptRepository.save(attempt);
                failureCount++;
            }
        }

        // Determine overall notification status
        if (failureCount == 0 && successCount > 0) {
            notification.setStatus(NotificationStatus.DELIVERED);
        } else if (successCount > 0 && failureCount > 0) {
            notification.setStatus(NotificationStatus.DELIVERED);
        } else {
            notification.setStatus(NotificationStatus.FAILED);
        }

        notificationRepository.save(notification);
        log.info("Completed delivery processing for notificationId='{}', final status='{}'",
                notificationId, notification.getStatus());
    }
}
