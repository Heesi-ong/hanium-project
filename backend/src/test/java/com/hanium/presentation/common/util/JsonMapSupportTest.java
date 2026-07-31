package com.hanium.presentation.common.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMapSupportTest {

    @Test
    void copiesOnlyStringKeyedEntriesAndPreservesNullValues() {
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put("score", 88);
        source.put("optional", null);
        source.put(123, "ignored");

        Map<String, Object> result = JsonMapSupport.copyStringKeyedMap(source);

        assertThat(result)
                .containsEntry("score", 88)
                .containsEntry("optional", null)
                .doesNotContainValue("ignored");
    }

    @Test
    void copiesMapItemsFromListAndFiltersNonMapItems() {
        Map<Object, Object> mixedKeys = new LinkedHashMap<>();
        mixedKeys.put("name", "model-a");
        mixedKeys.put(1, "ignored");

        List<Map<String, Object>> result = JsonMapSupport.copyStringKeyedMapList(
                List.of(mixedKeys, "not-a-map", Map.of("name", "model-b"))
        );

        assertThat(result).containsExactly(
                Map.of("name", "model-a"),
                Map.of("name", "model-b")
        );
    }

    @Test
    void returnsEmptyCollectionsForUnsupportedValues() {
        assertThat(JsonMapSupport.copyStringKeyedMap("not-a-map")).isEmpty();
        assertThat(JsonMapSupport.copyStringKeyedMapList("not-a-list")).isEmpty();
    }
}
