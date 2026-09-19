package com.demo.notification.channel;

import com.demo.notification.domain.types.ChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry managing available {@link ChannelProvider} implementations.
 * <p>
 * Automatically populated by Spring dependency injection with all active provider beans.
 * Enables the Strategy Pattern by routing messages dynamically to the matching provider.
 */
@Component
public class ChannelProviderRegistry {

    private final Map<ChannelType, ChannelProvider> providerMap = new EnumMap<>(ChannelType.class);

    public ChannelProviderRegistry(List<ChannelProvider> providers) {
        if (providers != null) {
            providers.forEach(p -> providerMap.put(p.getChannelType(), p));
        }
    }

    /**
     * Resolves the channel provider registered for the given {@link ChannelType}.
     *
     * @param channelType target channel type
     * @return Optional containing the provider if found
     */
    public Optional<ChannelProvider> getProvider(ChannelType channelType) {
        return Optional.ofNullable(providerMap.get(channelType));
    }

    /**
     * Checks if a channel provider exists and reports healthy availability.
     *
     * @param channelType target channel type
     * @return true if provider is registered and active
     */
    public boolean isChannelAvailable(ChannelType channelType) {
        ChannelProvider provider = providerMap.get(channelType);
        return provider != null && provider.isAvailable();
    }
}
