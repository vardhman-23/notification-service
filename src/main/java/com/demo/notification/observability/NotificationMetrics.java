package com.demo.notification.observability;

import com.demo.notification.domain.types.ChannelType;
import com.demo.notification.domain.types.ErrorCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Enterprise metrics publisher exposing custom operational telemetry via Spring Boot Actuator and Micrometer.
 * <p>
 * Emits dimensional counters and timers for real-time Prometheus scraping and Grafana dashboards.
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;
    private final Counter receivedCounter;
    private final Counter duplicateSuppressedCounter;
    private final Counter rateLimitedCounter;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.receivedCounter = Counter.builder("notifications.received.total")
                .description("Total number of notification requests ingested")
                .register(registry);

        this.duplicateSuppressedCounter = Counter.builder("notifications.duplicate.suppressed.total")
                .description("Total number of duplicate notifications suppressed via idempotency")
                .register(registry);

        this.rateLimitedCounter = Counter.builder("notifications.ratelimited.total")
                .description("Total number of notification submissions blocked by rate limiting")
                .register(registry);
    }

    /**
     * Increments the ingested notifications counter.
     */
    public void incrementReceived() {
        receivedCounter.increment();
    }

    /**
     * Increments the duplicate suppression counter.
     */
    public void incrementDuplicateSuppressed() {
        duplicateSuppressedCounter.increment();
    }

    /**
     * Increments the rate limit counter.
     */
    public void incrementRateLimited() {
        rateLimitedCounter.increment();
    }

    /**
     * Records a successful delivery outcome tagged by channel and provider.
     *
     * @param channel  delivery channel
     * @param provider provider name
     */
    public void incrementDelivered(ChannelType channel, String provider) {
        Counter.builder("notifications.delivered.total")
                .description("Total successful notification dispatches")
                .tag("channel", channel != null ? channel.name() : "UNKNOWN")
                .tag("provider", provider != null ? provider : "UNKNOWN")
                .register(registry)
                .increment();
    }

    /**
     * Records a failed delivery attempt tagged by channel, provider, and error category.
     *
     * @param channel       delivery channel
     * @param provider      provider name
     * @param errorCategory category classification
     */
    public void incrementFailed(ChannelType channel, String provider, ErrorCategory errorCategory) {
        Counter.builder("notifications.failed.total")
                .description("Total failed notification delivery attempts")
                .tag("channel", channel != null ? channel.name() : "UNKNOWN")
                .tag("provider", provider != null ? provider : "UNKNOWN")
                .tag("error_category", errorCategory != null ? errorCategory.name() : "UNCLASSIFIED")
                .register(registry)
                .increment();
    }

    /**
     * Records an unrecoverable message routed to the Dead Letter Queue (DLQ).
     *
     * @param reason compliance reason
     */
    public void incrementDeadLetter(String reason) {
        Counter.builder("notifications.deadletter.total")
                .description("Total notifications routed to Dead Letter Queue")
                .tag("reason", reason != null ? reason : "PERMANENT_FAILURE")
                .register(registry)
                .increment();
    }

    /**
     * Records delivery latency for a provider call.
     *
     * @param channel        delivery channel
     * @param provider       provider name
     * @param durationMillis elapsed time in milliseconds
     */
    public void recordDeliveryLatency(ChannelType channel, String provider, long durationMillis) {
        Timer.builder("notifications.delivery.latency")
                .description("Latency of downstream provider dispatch calls")
                .tag("channel", channel != null ? channel.name() : "UNKNOWN")
                .tag("provider", provider != null ? provider : "UNKNOWN")
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }
}

