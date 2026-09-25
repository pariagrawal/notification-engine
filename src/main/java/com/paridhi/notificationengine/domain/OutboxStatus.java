package com.paridhi.notificationengine.domain;

/** Publication state of a row in the transactional outbox. */
public enum OutboxStatus {

    PENDING,
    PUBLISHED,
    /** Publication failed more times than {@code notification.outbox.max-attempts}. */
    FAILED
}
