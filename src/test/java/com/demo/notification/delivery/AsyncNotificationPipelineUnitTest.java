package com.demo.notification.delivery;

import com.demo.notification.api.filter.CorrelationIdFilter;
import com.demo.notification.domain.model.Notification;
import com.demo.notification.domain.repository.NotificationRepository;
import com.demo.notification.domain.types.NotificationStatus;
import com.demo.notification.exception.ResourceNotFoundException;
import com.demo.notification.service.RoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncNotificationPipelineUnitTest {

    private NotificationRepository notificationRepository;
    private RoutingService routingService;
    private DeliveryWorker deliveryWorker;
    private AsyncNotificationPipeline pipeline;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        routingService = mock(RoutingService.class);
        deliveryWorker = mock(DeliveryWorker.class);

        pipeline = new AsyncNotificationPipeline(notificationRepository, routingService, deliveryWorker);
        ReflectionTestUtils.setField(pipeline, "autoDispatchEnabled", true);
    }

    @Test
    @DisplayName("OnNotificationAccepted: executes pipeline when autoDispatch is enabled")
    void testAutoDispatchEnabled() {
        UUID id = UUID.randomUUID();
        NotificationAcceptedEvent event = new NotificationAcceptedEvent(id, "test-corr-id");

        Notification notification = Notification.builder()
                .notificationId(id)
                .status(NotificationStatus.ACCEPTED)
                .build();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        pipeline.onNotificationAccepted(event);

        verify(routingService).routeNotification(id);
        verify(deliveryWorker).processDelivery(id);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("OnNotificationAccepted: skips pipeline when autoDispatch is disabled")
    void testAutoDispatchDisabled() {
        ReflectionTestUtils.setField(pipeline, "autoDispatchEnabled", false);
        UUID id = UUID.randomUUID();
        NotificationAcceptedEvent event = new NotificationAcceptedEvent(id);

        pipeline.onNotificationAccepted(event);

        verify(notificationRepository, never()).findById(id);
        verify(routingService, never()).routeNotification(id);
    }

    @Test
    @DisplayName("ExecutePipeline: skips routing if status is already ROUTED")
    void testExecutePipelineAlreadyRouted() {
        UUID id = UUID.randomUUID();
        Notification notification = Notification.builder()
                .notificationId(id)
                .status(NotificationStatus.ROUTED)
                .build();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));

        pipeline.executePipeline(id);

        verify(routingService, never()).routeNotification(id);
        verify(deliveryWorker).processDelivery(id);
    }

    @Test
    @DisplayName("ExecutePipeline: throws ResourceNotFoundException when notification does not exist")
    void testExecutePipelineNotFound() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pipeline.executePipeline(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

