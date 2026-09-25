package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.UserPreference;

public record PreferenceResponse(
        Long id,
        String userId,
        Channel channel,
        boolean enabled,
        String destination,
        String locale,
        String timeZone,
        Integer quietHoursStart,
        Integer quietHoursEnd) {

    public static PreferenceResponse of(UserPreference preference) {
        return new PreferenceResponse(
                preference.getId(),
                preference.getUserId(),
                preference.getChannel(),
                preference.isEnabled(),
                preference.getDestination(),
                preference.getLocale(),
                preference.getTimeZone(),
                preference.getQuietHoursStart(),
                preference.getQuietHoursEnd());
    }
}
