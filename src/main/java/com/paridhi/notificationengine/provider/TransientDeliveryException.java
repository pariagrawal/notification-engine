package com.paridhi.notificationengine.provider;

/**
 * Delivery failed for a reason that may not recur: a timeout, a 5xx, provider throttling.
 * The channel consumer lets this propagate so Kafka redelivers it with backoff.
 */
public class TransientDeliveryException extends RuntimeException {

    public TransientDeliveryException(String message) {
        super(message);
    }

    public TransientDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
