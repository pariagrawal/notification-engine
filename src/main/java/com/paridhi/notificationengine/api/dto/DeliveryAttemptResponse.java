package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.DeliveryAttempt;
import com.paridhi.notificationengine.domain.DeliveryOutcome;
import java.time.Instant;

public record DeliveryAttemptResponse(
        int attemptNo,
        DeliveryOutcome outcome,
        String provider,
        String providerMessageId,
        String error,
        Long latencyMs,
        Instant createdAt) {

    public static DeliveryAttemptResponse of(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.getAttemptNo(),
                attempt.getOutcome(),
                attempt.getProvider(),
                attempt.getProviderMessageId(),
                attempt.getError(),
                attempt.getLatencyMs(),
                attempt.getCreatedAt());
    }
}
