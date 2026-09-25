package com.paridhi.notificationengine.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * @param code    stable machine-readable error code, e.g. {@code RATE_LIMITED}
 * @param details field-level validation messages, omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        Map<String, String> details) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(Instant.now(), status, code, message, Map.of());
    }

    public static ApiError of(int status, String code, String message, Map<String, String> details) {
        return new ApiError(Instant.now(), status, code, message, details);
    }
}
