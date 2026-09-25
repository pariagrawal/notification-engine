package com.paridhi.notificationengine.exception;

/** The request is well-formed but cannot be fulfilled as written. Surfaces as HTTP 422. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
