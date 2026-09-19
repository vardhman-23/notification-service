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
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.exception.PermanentProviderException;
import com.demo.notification.exception.ResourceNotFoundException;
import com.demo.notification.observability.NotificationMetrics;
import com.demo.notification.util.DataMaskingUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Worker component responsible for executing staged delivery attempts across configured channels.
 * <p>
 * <b>Strict Database Transaction Boundary Scoping:</b>
 * The main orchestration method {@link #processDelivery(UUID)} is non-transactional.
 * Long-running external channel provider HTTP calls and Resilience4j retries execute
 * entirely outside of database transactions, protecting the HikariCP connection pool
 * from thread starvation and connection exhaustion.
 * Database writes are confined to short, dedicated transactional helper methods.
 * <p>
 * <b>Dead Letter Routing (DLQ):</b>
 * Messages that permanently fail or exhaust all retry attempts transition to terminal state
 * {@link NotificationStatus#DEAD_LETTER} and record an immutable {@link AuditAction#ROUTED_TO_DEAD_LETTER}
 * audit trail for compliance auditing.
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
    private final NotificationMetrics notificationMetrics;

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
     * Orchestrates delivery attempts for a notification.
     * <p>
     * NOTE: Deliberately non-transactional so external downstream provider calls do not hold DB connections.
     *
     * @param notificationId unique identifier of the notification
     */
    public void processDelivery(UUID notificationId) {
        Notification notification = startDelivery(notificationId);
        List<DeliveryAttempt> attempts = new ArrayList<>(notification.getDeliveryAttempts());
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
                recordAttemptPreFlightFailure(attempt, "Recipient metadata missing", notificationId);
                notificationMetrics.incrementFailed(attempt.getChannel(), attempt.getProvider(), ErrorCategory.INVALID_RECIPIENT);
                failureCount++;
                continue;
            }

            ChannelProvider provider = channelProviderRegistry.getProvider(attempt.getChannel())
                    .orElse(null);

            if (provider == null) {
                log.error("No provider registered for channel '{}'", attempt.getChannel());
                recordAttemptPreFlightFailure(attempt, "No provider available for channel " + attempt.getChannel(), notificationId);
                notificationMetrics.incrementFailed(attempt.getChannel(), "NONE", ErrorCategory.PERMANENT_PROVIDER_REJECTION);
                failureCount++;
                continue;
            }

            // Downstream provider dispatch sits ENTIRELY outside database transaction
            long startTime = System.currentTimeMillis();
            try {
                DeliveryResponse response = providerDispatchService.dispatch(provider, notification, recipient, attempt);
                long duration = System.currentTimeMillis() - startTime;
                notificationMetrics.recordDeliveryLatency(attempt.getChannel(), provider.getProviderName(), duration);

                if (response.isSuccessful()) {
                    recordAttemptSuccess(notificationId, attempt, response, recipient);
                    notificationMetrics.incrementDelivered(attempt.getChannel(), provider.getProviderName());
                    successCount++;
                } else {
                    recordAttemptGenericFailure(notificationId, attempt, new RuntimeException(response.getErrorMessage()), recipient);
                    notificationMetrics.incrementFailed(attempt.getChannel(), provider.getProviderName(), response.getErrorCategory());
                    failureCount++;
                }

            } catch (PermanentProviderException ex) {
                long duration = System.currentTimeMillis() - startTime;
                notificationMetrics.recordDeliveryLatency(attempt.getChannel(), provider.getProviderName(), duration);
                log.error("Permanent failure encountered for recipient '{}' on channel '{}': {}",
                        recipient.getRecipientId(), attempt.getChannel(), ex.getMessage());

                recordAttemptPermanentFailure(notificationId, attempt, ex, recipient);
                notificationMetrics.incrementFailed(attempt.getChannel(), provider.getProviderName(), ex.getErrorCategory());
                failureCount++;

            } catch (Exception ex) {
                long duration = System.currentTimeMillis() - startTime;
                notificationMetrics.recordDeliveryLatency(attempt.getChannel(), provider.getProviderName(), duration);
                log.error("Unexpected error delivering to recipient '{}': {}", recipient.getRecipientId(), ex.getMessage());

                recordAttemptGenericFailure(notificationId, attempt, ex, recipient);
                notificationMetrics.incrementFailed(attempt.getChannel(), provider.getProviderName(), ErrorCategory.TRANSIENT_PROVIDER_FAILURE);
                failureCount++;
            }
        }

        finalizeDelivery(notificationId, successCount, failureCount, attempts.size());
    }

    /**
     * Scoped transaction: marks notification status as DELIVERING and loads state.
     */
    @Transactional
    public Notification startDelivery(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));
        notification.setStatus(NotificationStatus.DELIVERING);
        Notification saved = notificationRepository.save(notification);
        return saved != null ? saved : notification;
    }

    /**
     * Scoped transaction: records a successful delivery attempt and audit log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptSuccess(UUID notificationId,
                                     DeliveryAttempt attempt,
                                     DeliveryResponse response,
                                     NotificationRecipient recipient) {
        attempt.setStatus(DeliveryStatus.SENT);
        attempt.setSentAt(Instant.now());
        attempt.setExecutionTime(response.getExecutionTimeMs());
        attempt.setProviderResponseCode(response.getProviderResponseCode());
        deliveryAttemptRepository.save(attempt);

        AuditLog successAudit = AuditLog.builder()
                .notificationId(notificationId)
                .action(AuditAction.DELIVERED)
                .metadataReason(String.format("Delivered successfully to recipient '%s' via %s",
                        recipient.getRecipientId(), attempt.getChannel()))
                .sanitizedPayloadSummary(String.format("Channel: %s, Destination: %s, ResponseCode: %s, TimeMs: %d",
                        attempt.getChannel(), DataMaskingUtils.maskDestination(recipient.getDestination()),
                        response.getProviderResponseCode(), response.getExecutionTimeMs()))
                .timestamp(Instant.now())
                .build();
        auditLogRepository.save(successAudit);
    }

    /**
     * Scoped transaction: records a permanent failure without retries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptPermanentFailure(UUID notificationId,
                                             DeliveryAttempt attempt,
                                             PermanentProviderException ex,
                                             NotificationRecipient recipient) {
        attempt.setStatus(DeliveryStatus.FAILED);
        attempt.setErrorCategory(ex.getErrorCategory());
        attempt.setErrorMessage("Permanent rejection: " + ex.getMessage());
        attempt.setProviderResponseCode(String.valueOf(ex.getStatusCode()));
        deliveryAttemptRepository.save(attempt);

        AuditLog permanentFailAudit = AuditLog.builder()
                .notificationId(notificationId)
                .action(AuditAction.FAILED)
                .metadataReason(String.format("Permanent provider rejection (HTTP %d) for recipient '%s' on %s: %s",
                        ex.getStatusCode(), recipient.getRecipientId(), attempt.getChannel(), ex.getMessage()))
                .sanitizedPayloadSummary(String.format("Channel: %s, Destination: %s, ErrorCategory: %s, StatusCode: %d",
                        attempt.getChannel(), DataMaskingUtils.maskDestination(recipient.getDestination()),
                        ex.getErrorCategory(), ex.getStatusCode()))
                .timestamp(Instant.now())
                .build();
        auditLogRepository.save(permanentFailAudit);
    }

    /**
     * Scoped transaction: records unexpected failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptGenericFailure(UUID notificationId,
                                           DeliveryAttempt attempt,
                                           Exception ex,
                                           NotificationRecipient recipient) {
        attempt.setStatus(DeliveryStatus.FAILED);
        attempt.setErrorMessage("Unexpected failure: " + ex.getMessage());
        deliveryAttemptRepository.save(attempt);
    }

    /**
     * Scoped transaction: records pre-flight validation failure (missing recipient or channel provider).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttemptPreFlightFailure(DeliveryAttempt attempt, String errorMsg, UUID notificationId) {
        attempt.setStatus(DeliveryStatus.FAILED);
        attempt.setErrorMessage(errorMsg);
        deliveryAttemptRepository.save(attempt);
    }

    /**
     * Scoped transaction: computes final aggregate status and routes unrecoverable messages to DLQ.
     */
    @Transactional
    public void finalizeDelivery(UUID notificationId, int successCount, int failureCount, int totalAttempts) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElse(null);
        if (notification == null) {
            return;
        }

        if (successCount > 0) {
            notification.setStatus(NotificationStatus.DELIVERED);
        } else {
            // Unrecoverable failures routed to DEAD_LETTER status (Dead Letter Queue routing)
            notification.setStatus(NotificationStatus.DEAD_LETTER);

            AuditLog dlqAudit = AuditLog.builder()
                    .notificationId(notificationId)
                    .action(AuditAction.ROUTED_TO_DEAD_LETTER)
                    .metadataReason(String.format("All delivery attempts failed (%d/%d). Routed notification to Dead Letter Queue for compliance audit.",
                            failureCount, totalAttempts))
                    .sanitizedPayloadSummary(String.format("TerminalState: DEAD_LETTER, FailedAttempts: %d/%d", failureCount, totalAttempts))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(dlqAudit);
            notificationMetrics.incrementDeadLetter("DELIVERY_EXHAUSTED");
        }

        notificationRepository.save(notification);
        log.info("Completed delivery processing for notificationId='{}', final status='{}'",
                notificationId, notification.getStatus());
    }
}
