package com.hanium.presentation.common.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonMapSupport {

    private JsonMapSupport() {
    }

    public static Map<String, Object> copyStringKeyedMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entryValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, entryValue);
            }
        });
        return result;
    }

    public static List<Map<String, Object>> copyStringKeyedMapList(Object value) {
        if (!(value instanceof List<?> source)) {
            return List.of();
        }

        return source.stream()
                .filter(item -> item instanceof Map<?, ?>)
                .map(JsonMapSupport::copyStringKeyedMap)
                .toList();
    }
}
