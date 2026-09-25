package com.paridhi.notificationengine.exception;

/** A requested resource does not exist. Surfaces as HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
