package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.domain.Channel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves the provider responsible for a channel. */
@Component
public class ProviderRegistry {

    private final Map<Channel, NotificationProvider> byChannel = new EnumMap<>(Channel.class);

    public ProviderRegistry(List<NotificationProvider> providers) {
        for (NotificationProvider provider : providers) {
            NotificationProvider previous = byChannel.put(provider.channel(), provider);
            if (previous != null) {
                throw new IllegalStateException("two providers registered for channel %s: %s and %s"
                        .formatted(provider.channel(), previous.name(), provider.name()));
            }
        }
    }

    public NotificationProvider require(Channel channel) {
        NotificationProvider provider = byChannel.get(channel);
        if (provider == null) {
            throw new PermanentDeliveryException("no provider registered for channel " + channel);
        }
        return provider;
    }
}
