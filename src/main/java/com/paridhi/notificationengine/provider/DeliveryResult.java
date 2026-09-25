package com.paridhi.notificationengine.provider;

/**
 * What a provider reports back about one send.
 *
 * @param providerMessageId identifier the downstream provider assigned, if any
 * @param latencyMs         wall-clock time the call took
 */
public record DeliveryResult(String providerMessageId, long latencyMs) {
}
