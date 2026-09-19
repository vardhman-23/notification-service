package com.schwab.notification.channel;

import com.schwab.notification.domain.types.ChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ChannelProviderRegistry {

    private final Map<ChannelType, ChannelProvider> providerMap = new EnumMap<>(ChannelType.class);

    public ChannelProviderRegistry(List<ChannelProvider> providers) {
        if (providers != null) {
            providers.forEach(p -> providerMap.put(p.getChannelType(), p));
        }
    }

    public Optional<ChannelProvider> getProvider(ChannelType channelType) {
        return Optional.ofNullable(providerMap.get(channelType));
    }

    public boolean isChannelAvailable(ChannelType channelType) {
        ChannelProvider provider = providerMap.get(channelType);
        return provider != null && provider.isAvailable();
    }
}

