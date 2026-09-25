package com.paridhi.notificationengine.exception;

/** A template referenced a variable the caller did not supply. Surfaces as HTTP 422. */
public class TemplateRenderException extends RuntimeException {

    public TemplateRenderException(String message) {
        super(message);
    }
}
