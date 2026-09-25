package com.paridhi.notificationengine.exception;

/**
 * Another request claimed the same idempotency key and has not committed yet. Surfaces as
 * HTTP 409 so the caller retries rather than assuming the send was lost.
 */
public class DuplicateInFlightException extends RuntimeException {

    public DuplicateInFlightException(String message) {
        super(message);
    }
}
