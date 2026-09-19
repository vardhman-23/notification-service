package com.schwab.notification.delivery;

import com.schwab.notification.domain.model.Notification;
import com.schwab.notification.domain.repository.NotificationRepository;
import com.schwab.notification.domain.types.NotificationStatus;
import com.schwab.notification.service.RoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Asynchronous orchestrator that listens for accepted notifications and drives them
 * through Channel Routing (Strategy Pattern + Intelligent Fallback) and resilient Delivery Execution.
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

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationAccepted(NotificationAcceptedEvent event) {
        if (!autoDispatchEnabled) {
            log.debug("Auto-dispatch is disabled; skipping automatic routing for notificationId='{}'", event.getNotificationId());
            return;
        }

        log.info("Processing asynchronous notification pipeline for notificationId='{}'", event.getNotificationId());
        executePipeline(event.getNotificationId());
    }

    /**
     * Executes the end-to-end routing and delivery pipeline for a given notification ID.
     * Can be invoked asynchronously by event listener or synchronously via controller dispatch.
     */
    public void executePipeline(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found: " + notificationId));

        if (notification.getStatus() == NotificationStatus.ACCEPTED) {
            log.info("Executing routing stage for notificationId='{}'", notificationId);
            routingService.routeNotification(notificationId);
        }

        log.info("Executing delivery stage for notificationId='{}'", notificationId);
        deliveryWorker.processDelivery(notificationId);
    }
}

