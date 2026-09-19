package com.schwab.notification.delivery;

import com.schwab.notification.channel.ChannelProvider;
import com.schwab.notification.channel.DeliveryResponse;
import com.schwab.notification.domain.model.AuditLog;
import com.schwab.notification.domain.model.DeliveryAttempt;
import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.model.NotificationRecipient;
import com.schwab.notification.domain.repository.AuditLogRepository;
import com.schwab.notification.domain.repository.DeliveryAttemptRepository;
import com.schwab.notification.domain.types.AuditAction;
import com.schwab.notification.domain.types.DeliveryStatus;
import com.schwab.notification.exception.PermanentProviderException;
import com.schwab.notification.exception.TransientProviderException;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProviderDispatchService {

    private final RetryRegistry retryRegistry;
    private final AuditLogRepository auditLogRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    @PostConstruct
    public void initRetryListener() {
        retryRegistry.retry("notificationDeliveryRetry").getEventPublisher()
                .onRetry(event -> {
                    log.warn("Resilience4j retry triggered for attempt #{}, last error: {}",
                            event.getNumberOfRetryAttempts(), event.getLastThrowable().getMessage());
                });
    }

    @Retry(name = "notificationDeliveryRetry", fallbackMethod = "onRetryExhausted")
    public DeliveryResponse dispatch(ChannelProvider provider,
                                     Notification notification,
                                     NotificationRecipient recipient,
                                     DeliveryAttempt attempt) {
        int currentAttempt = attempt.getAttemptNumber();
        log.info("Dispatching attempt #{} via provider '{}' to recipient '{}'",
                currentAttempt, provider.getProviderName(), recipient.getRecipientId());

        try {
            DeliveryResponse response = provider.send(notification, recipient);
            attempt.setStatus(response.getStatus());
            attempt.setProviderResponseCode(response.getProviderResponseCode());
            attempt.setExecutionTime(response.getExecutionTimeMs());
            return response;
        } catch (TransientProviderException ex) {
            // Transient error: classify, record RETRY_SCHEDULED, and rethrow to trigger Resilience4j backoff
            log.warn("Transient error on attempt #{} for recipient '{}': {}",
                    currentAttempt, recipient.getRecipientId(), ex.getMessage());

            attempt.setStatus(DeliveryStatus.RETRYING);
            attempt.setAttemptNumber(currentAttempt + 1);
            attempt.setErrorCategory(ex.getErrorCategory());
            attempt.setErrorMessage(ex.getMessage());
            attempt.setProviderResponseCode(String.valueOf(ex.getStatusCode()));
            deliveryAttemptRepository.save(attempt);

            // Record RETRY_SCHEDULED in AuditLog
            AuditLog retryAudit = AuditLog.builder()
                    .notificationId(notification.getNotificationId())
                    .action(AuditAction.RETRY_SCHEDULED)
                    .metadataReason(String.format("Retry scheduled for recipient '%s' on %s (attempt #%d): %s",
                            recipient.getRecipientId(), provider.getChannelType(), currentAttempt, ex.getMessage()))
                    .sanitizedPayloadSummary(String.format("Channel: %s, ErrorCode: %d, Category: %s",
                            provider.getChannelType(), ex.getStatusCode(), ex.getErrorCategory()))
                    .timestamp(Instant.now())
                    .build();
            auditLogRepository.save(retryAudit);

            throw ex;
        } catch (PermanentProviderException ex) {
            // Permanent error: must not be retried, rethrow to terminate immediately
            log.error("Permanent error on attempt #{} for recipient '{}': {}",
                    currentAttempt, recipient.getRecipientId(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * Fallback method executed when all Resilience4j retries are exhausted for TransientProviderException.
     */
    public DeliveryResponse onRetryExhausted(ChannelProvider provider,
                                             Notification notification,
                                             NotificationRecipient recipient,
                                             DeliveryAttempt attempt,
                                             TransientProviderException ex) {
        log.error("All retries exhausted for recipient '{}' on channel '{}' after {} attempts",
                recipient.getRecipientId(), provider.getChannelType(), attempt.getAttemptNumber());

        attempt.setStatus(DeliveryStatus.FAILED);
        attempt.setErrorCategory(ex.getErrorCategory());
        attempt.setErrorMessage("Exhausted retries: " + ex.getMessage());
        attempt.setProviderResponseCode(String.valueOf(ex.getStatusCode()));
        deliveryAttemptRepository.save(attempt);

        // Record final FAILED audit log
        AuditLog failedAudit = AuditLog.builder()
                .notificationId(notification.getNotificationId())
                .action(AuditAction.FAILED)
                .metadataReason(String.format("Delivery failed after exhausting retries for recipient '%s' on %s: %s",
                        recipient.getRecipientId(), provider.getChannelType(), ex.getMessage()))
                .sanitizedPayloadSummary(String.format("Channel: %s, TotalAttempts: %d, FinalError: %d",
                        provider.getChannelType(), attempt.getAttemptNumber(), ex.getStatusCode()))
                .timestamp(Instant.now())
                .build();
        auditLogRepository.save(failedAudit);

        return DeliveryResponse.builder()
                .successful(false)
                .status(DeliveryStatus.FAILED)
                .providerResponseCode(String.valueOf(ex.getStatusCode()))
                .errorMessage("Exhausted retries: " + ex.getMessage())
                .errorCategory(ex.getErrorCategory())
                .build();
    }
}

