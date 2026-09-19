package com.demo.notification.delivery;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.exception.ResourceNotFoundException;
import com.demo.notification.service.RoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Asynchronous pipeline orchestrator driving accepted notifications through channel routing
 * and multi-channel provider delivery execution.
 * <p>
 * Listens for {@link NotificationAcceptedEvent} after the database transaction commits.
 * Restores cross-thread distributed tracing MDC context using {@code event.getCorrelationId()}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncNotificationPipeline {

    private final NotificationRepository notificationRepository;
    private final RoutingService routingService;
    private final DeliveryWorker deliveryWorker;

    @Value("${notification.auto-dispatch.enabled:true}")
    private boolean autoDispatchEnabled;

    /**
     * Transactional event listener triggered asynchronously after notification ingestion commits.
     *
     * @param event domain event carrying notification ID and tracing correlation ID
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationAccepted(NotificationAcceptedEvent event) {
        String correlationId = event.getCorrelationId();
        if (StringUtils.hasText(correlationId)) {
            MDC.put(CorrelationIdFilter.MDC_CORRELATION_ID_KEY, correlationId);
        }

        try {
            if (!autoDispatchEnabled) {
                log.debug("Auto-dispatch is disabled; skipping automatic routing pipeline for notificationId='{}'",
                        event.getNotificationId());
                return;
            }

            log.info("Processing asynchronous notification pipeline for notificationId='{}'", event.getNotificationId());
            executePipeline(event.getNotificationId());
        } finally {
            if (StringUtils.hasText(correlationId)) {
                MDC.remove(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
            }
        }
    }

    /**
     * Executes the end-to-end routing and delivery stages for a notification aggregate.
     * <p>
     * Safe for asynchronous invocation or manual on-demand execution via controller dispatch.
     *
     * @param notificationId unique identifier of the notification
     */
    public void executePipeline(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + notificationId));

        if (notification.getStatus() == NotificationStatus.ACCEPTED) {
            log.info("Executing routing stage for notificationId='{}'", notificationId);
            routingService.routeNotification(notificationId);
        }

        log.info("Executing delivery stage for notificationId='{}'", notificationId);
        deliveryWorker.processDelivery(notificationId);
    }
}
