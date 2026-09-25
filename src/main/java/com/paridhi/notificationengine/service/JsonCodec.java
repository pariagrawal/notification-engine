package com.paridhi.notificationengine.service;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin wrapper over the application {@link ObjectMapper}, so the JSON dependency shows up
 * in one place instead of being threaded through every service and consumer.
 */
@Component
public class JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }

    public Map<String, String> readStringMap(String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, STRING_MAP);
    }
}
