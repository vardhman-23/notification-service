package com.demo.notification;

import com.demo.notification.channel.ChannelProvider;
import com.demo.notification.channel.ChannelProviderRegistry;
import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.ErrorCategory;
import com.demo.notification.observability.NotificationChannelsHealthIndicator;
import com.demo.notification.observability.NotificationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationMetricsAndHealthTest {

    private MeterRegistry meterRegistry;
    private NotificationMetrics notificationMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        notificationMetrics = new NotificationMetrics(meterRegistry);
    }

    @Test
    @DisplayName("NotificationMetrics: increments received, duplicate, rate-limited, delivered, and failed counters")
    void testMetricsCounters() {
        notificationMetrics.incrementReceived();
        notificationMetrics.incrementReceived();
        assertThat(meterRegistry.get("notifications.received.total").counter().count()).isEqualTo(2.0);

        notificationMetrics.incrementDuplicateSuppressed();
        assertThat(meterRegistry.get("notifications.duplicate.suppressed.total").counter().count()).isEqualTo(1.0);

        notificationMetrics.incrementRateLimited();
        assertThat(meterRegistry.get("notifications.ratelimited.total").counter().count()).isEqualTo(1.0);

        notificationMetrics.incrementDelivered(ChannelType.EMAIL, "AWS_SES");
        assertThat(meterRegistry.get("notifications.delivered.total")
                .tag("channel", "EMAIL")
                .tag("provider", "AWS_SES")
                .counter().count()).isEqualTo(1.0);

        notificationMetrics.incrementFailed(ChannelType.SMS, "TWILIO", ErrorCategory.RATE_LIMIT_EXCEEDED);
        assertThat(meterRegistry.get("notifications.failed.total")
                .tag("channel", "SMS")
                .tag("provider", "TWILIO")
                .tag("error_category", "RATE_LIMIT_EXCEEDED")
                .counter().count()).isEqualTo(1.0);

        notificationMetrics.incrementDeadLetter("DELIVERY_EXHAUSTED");
        assertThat(meterRegistry.get("notifications.deadletter.total")
                .tag("reason", "DELIVERY_EXHAUSTED")
                .counter().count()).isEqualTo(1.0);

        notificationMetrics.recordDeliveryLatency(ChannelType.EMAIL, "AWS_SES", 150);
        assertThat(meterRegistry.get("notifications.delivery.latency")
                .tag("channel", "EMAIL")
                .tag("provider", "AWS_SES")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("HealthIndicator: returns UP when EMAIL channel provider is operational")
    void testHealthIndicatorUp() {
        ChannelProvider emailProvider = mock(ChannelProvider.class);
        when(emailProvider.getChannelType()).thenReturn(ChannelType.EMAIL);
        when(emailProvider.isAvailable()).thenReturn(true);

        ChannelProviderRegistry registry = new ChannelProviderRegistry(List.of(emailProvider));
        NotificationChannelsHealthIndicator indicator = new NotificationChannelsHealthIndicator(registry);

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("EMAIL")).isEqualTo("OPERATIONAL");
    }

    @Test
    @DisplayName("HealthIndicator: returns DOWN when critical EMAIL fallback is unavailable")
    void testHealthIndicatorDown() {
        ChannelProviderRegistry emptyRegistry = new ChannelProviderRegistry(List.of());
        NotificationChannelsHealthIndicator indicator = new NotificationChannelsHealthIndicator(emptyRegistry);

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error")).isEqualTo("Critical fallback channel EMAIL is unavailable");
    }
}

