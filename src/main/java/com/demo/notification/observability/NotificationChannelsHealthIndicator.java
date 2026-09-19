package com.demo.notification.observability;

import com.demo.notification.channel.ChannelProviderRegistry;
import com.demo.notification.domain.types.ChannelType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Deep health indicator checking the operational status of external notification channel providers.
 * <p>
 * Exposed under {@code /actuator/health} alongside database and cache health to provide
 * complete dependency visibility for Kubernetes liveness and readiness probes.
 */
@Component("notificationChannels")
@RequiredArgsConstructor
public class NotificationChannelsHealthIndicator implements HealthIndicator {

    private final ChannelProviderRegistry channelProviderRegistry;

    @Override
    public Health health() {
        Map<String, Object> details = new EnumMap<>(ChannelType.class).keySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ChannelType::name,
                        ch -> channelProviderRegistry.isChannelAvailable(ch) ? "UP" : "UNAVAILABLE"
                ));

        // Dynamically evaluate all ChannelType values
        for (ChannelType channel : ChannelType.values()) {
            boolean available = channelProviderRegistry.isChannelAvailable(channel);
            details.put(channel.name(), available ? "OPERATIONAL" : "UNAVAILABLE");
        }

        // Email is the foundational regulatory fallback channel
        boolean emailAvailable = channelProviderRegistry.isChannelAvailable(ChannelType.EMAIL);

        if (emailAvailable) {
            return Health.up()
                    .withDetail("primaryFallbackChannel", "EMAIL: OPERATIONAL")
                    .withDetails(details)
                    .build();
        } else {
            return Health.down()
                    .withDetail("error", "Critical fallback channel EMAIL is unavailable")
                    .withDetails(details)
                    .build();
        }
    }
}

