package com.paridhi.notificationengine.api;

import com.paridhi.notificationengine.api.dto.PreferenceRequest;
import com.paridhi.notificationengine.api.dto.PreferenceResponse;
import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.service.PreferenceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/{userId}/preferences")
public class PreferenceController {

    private final PreferenceService preferences;

    public PreferenceController(PreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public List<PreferenceResponse> list(@PathVariable String userId) {
        return preferences.findAll(userId).stream().map(PreferenceResponse::of).toList();
    }

    @GetMapping("/{channel}")
    public PreferenceResponse get(@PathVariable String userId, @PathVariable Channel channel) {
        return preferences.find(userId, channel)
                .map(PreferenceResponse::of)
                .orElseThrow(() -> new NotFoundException(
                        "user %s has no %s preference".formatted(userId, channel)));
    }

    /** Creates or replaces the settings for one channel. */
    @PutMapping
    public PreferenceResponse upsert(@PathVariable String userId, @Valid @RequestBody PreferenceRequest request) {
        return PreferenceResponse.of(preferences.upsert(
                userId,
                request.channel(),
                request.enabled(),
                request.destination(),
                request.locale(),
                request.timeZone(),
                request.quietHoursStart(),
                request.quietHoursEnd()));
    }
}
