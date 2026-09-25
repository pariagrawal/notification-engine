package com.paridhi.notificationengine.domain;

/** Delivery channels supported by the engine. */
public enum Channel {

    EMAIL,
    SMS,
    PUSH;

    /** Lowercase form used when building Kafka topic names, e.g. {@code notifications.email}. */
    public String topicSegment() {
        return name().toLowerCase();
    }
}
