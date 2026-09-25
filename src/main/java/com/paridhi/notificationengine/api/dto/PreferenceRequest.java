package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.Channel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param destination     email address, E.164 phone number, or device token
 * @param quietHoursStart inclusive local hour (0-23) when quiet hours begin, or null
 * @param quietHoursEnd   exclusive local hour (0-23) when quiet hours end, or null
 */
public record PreferenceRequest(

        @NotNull
        Channel channel,

        // A Boolean, not a boolean: with a primitive, a body that simply left the field out
        // would bind to false and silently opt the user out of the channel.
        @NotNull
        Boolean enabled,

        @Size(max = 320)
        String destination,

        @Size(max = 16)
        String locale,

        @Size(max = 64)
        String timeZone,

        @Min(0) @Max(23)
        Integer quietHoursStart,

        @Min(0) @Max(23)
        Integer quietHoursEnd) {
}
