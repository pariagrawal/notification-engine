package com.paridhi.notificationengine.domain;

/** Result of one call into a channel provider. */
public enum DeliveryOutcome {

    SUCCESS,
    /** Failed, but worth retrying (timeout, 5xx, throttling). */
    TRANSIENT_FAILURE,
    /** Failed and retrying cannot help (malformed recipient, hard bounce). */
    PERMANENT_FAILURE
}
