package com.paridhi.notificationengine.api.dto;

import com.paridhi.notificationengine.domain.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * @param userId       who the notification is for; also the Kafka partition key
 * @param channel      EMAIL, SMS, or PUSH
 * @param templateCode template to render, e.g. {@code order-shipped}
 * @param data         template variables
 * @param recipient    overrides the destination stored in the user's preferences
 * @param locale       overrides the locale stored in the user's preferences
 * @param metadata     free-form annotations carried through to the provider
 */
public record SendNotificationRequest(

        @NotBlank @Size(max = 64)
        String userId,

        @NotNull
        Channel channel,

        @NotBlank @Size(max = 100)
        String templateCode,

        Map<String, String> data,

        @Size(max = 320)
        String recipient,

        @Size(max = 16)
        String locale,

        Map<String, String> metadata) {
}
