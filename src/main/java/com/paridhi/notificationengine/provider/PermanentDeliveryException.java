package com.paridhi.notificationengine.provider;

/**
 * Delivery failed in a way retrying cannot fix: an unroutable address, a hard bounce, a
 * rejected device token. The error handler is configured to send these straight to the
 * dead-letter topic rather than burn retries on them.
 */
public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }

    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
