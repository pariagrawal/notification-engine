package com.paridhi.notificationengine.domain;

/**
 * Lifecycle of a single notification.
 *
 * <pre>
 * QUEUED ──(outbox poller)──▶ PUBLISHED ──(channel consumer)──▶ SENT
 *    │                            │                              │
 *    │                            └──▶ FAILED ──(retries used)──▶ DEAD_LETTERED
 *    └──▶ SUPPRESSED (user preference / quiet hours)
 * </pre>
 */
public enum NotificationStatus {

    /** Persisted with its outbox event, not yet on Kafka. */
    QUEUED,
    /** The outbox poller handed it to Kafka. */
    PUBLISHED,
    /** A provider accepted it. */
    SENT,
    /** The last delivery attempt failed; a retry may still be pending. */
    FAILED,
    /** Retries are exhausted and the record now lives on a dead-letter topic. */
    DEAD_LETTERED,
    /** Never dispatched: the user opted out of this channel, or it was quiet hours. */
    SUPPRESSED;

    public boolean isTerminal() {
        return this == SENT || this == DEAD_LETTERED || this == SUPPRESSED;
    }
}
