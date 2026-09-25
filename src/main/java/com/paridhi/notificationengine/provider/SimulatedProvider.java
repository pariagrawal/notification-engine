package com.paridhi.notificationengine.provider;

import com.paridhi.notificationengine.config.NotificationProperties;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.messaging.event.NotificationMessage;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stand-in for a real vendor: validates the recipient, logs the message, and reports
 * success. Swap it out by registering your own {@link NotificationProvider} bean for the
 * same {@link Channel} — nothing else in the pipeline needs to change.
 *
 * <p>{@code notification.providers.simulated-failure-rate} makes a share of calls fail
 * with a {@link TransientDeliveryException}, which is how the retry and dead-letter paths
 * can be exercised without a broken vendor.
 */
public abstract class SimulatedProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProvider.class);

    private final NotificationProperties.Providers config;
    private final DoubleSupplier random;

    protected SimulatedProvider(NotificationProperties.Providers config) {
        this(config, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** Test seam: lets a test supply a deterministic "random" draw. */
    protected SimulatedProvider(NotificationProperties.Providers config, DoubleSupplier random) {
        this.config = config;
        this.random = random;
    }

    /** Rejects recipients this channel could never deliver to. */
    protected abstract void validateRecipient(String recipient);

    @Override
    public DeliveryResult send(NotificationMessage message) {
        long startedAt = System.nanoTime();
        if (message.recipient() == null || message.recipient().isBlank()) {
            throw new PermanentDeliveryException("no recipient for notification " + message.notificationId());
        }
        validateRecipient(message.recipient());

        if (config.getSimulatedFailureRate() > 0 && random.getAsDouble() < config.getSimulatedFailureRate()) {
            throw new TransientDeliveryException(
                    name() + " is temporarily unavailable (simulated failure)");
        }

        sleepIfConfigured();
        log.info("[{}] delivered notification {} to {} | subject={} | body={}",
                name(), message.notificationId(), message.recipient(), message.subject(), message.body());

        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new DeliveryResult(name().toLowerCase() + "-" + UUID.randomUUID(), latencyMs);
    }

    private void sleepIfConfigured() {
        long millis = config.getSimulatedLatency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TransientDeliveryException("interrupted while sending", ex);
        }
    }
}
