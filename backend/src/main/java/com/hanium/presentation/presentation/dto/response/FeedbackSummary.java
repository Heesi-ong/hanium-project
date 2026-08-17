package com.hanium.presentation.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hanium.presentation.common.util.JsonMapSupport;

import java.util.List;
import java.util.Map;

// ScoreSummary(P2-04)에 이어 두 번째로 옮기는 top-level 필드다. feedback도 그동안
// Map<String,Object>로 즉석에서 만들어지고 읽혀, 필드 누락이나 타입 변경을 컴파일 타임에
// 잡지 못했다. JSON 직렬화 결과(필드 이름/구조)는 기존 Map과 동일하게 유지해 API 계약과
// 프론트는 변경이 필요 없다. 과거에 저장된 결과 JSON은 디스크에서 다시 읽을 때 항상
// Map<String,Object>로 역직렬화되므로(JsonFileStorage.readObjectMap), from()이 그
// 과거 shape과 pipeline 기반 fallback 규칙(ResultSummaryResponse.extractFeedback()이
// 하던 것과 동일)을 그대로 관용적으로 받아들인다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedbackSummary(
        String generationMode,
        String model,
        boolean realApiUsed,
        String fallbackReason,
        String overall,
        List<String> strengths,
        List<String> improvements
) {

    private static final String DEFAULT_GENERATION_MODE = "UNKNOWN";
    private static final String DEFAULT_MODEL = "-";
    private static final String DEFAULT_FALLBACK_REASON = "-";

    public static FeedbackSummary unknown() {
        return new FeedbackSummary(
                DEFAULT_GENERATION_MODE,
                DEFAULT_MODEL,
                false,
                DEFAULT_FALLBACK_REASON,
                "",
                List.of(),
                List.of()
        );
    }

    // 과거 결과 JSON(디스크에서 다시 읽은 raw Map)이나 아직 실제 값이 확인되지 않은 입력을
    // 관용적으로 받아들인다. 저장된 feedback 값이 비어있거나 UNKNOWN이면 pipeline의 OpenAI
    // 메타데이터로 보완한다(ResultSummaryResponse.extractFeedback()이 하던 것과 동일한 규칙).
    public static FeedbackSummary from(Map<?, ?> raw, Map<String, Object> pipeline) {
        if (raw == null) {
            return fromPipeline(pipeline);
        }

        Map<String, Object> source = JsonMapSupport.copyStringKeyedMap(raw);
        Map<String, Object> normalizedPipeline = pipeline == null ? Map.of() : pipeline;
        Object sourceGenerationMode = source.getOrDefault("generationMode", DEFAULT_GENERATION_MODE);
        boolean sourceGenerationModeMeaningful = isMeaningful(sourceGenerationMode);

        return new FeedbackSummary(
                asString(
                        firstMeaningful(
                                sourceGenerationMode,
                                normalizedPipeline.get("openAiGenerationMode"),
                                DEFAULT_GENERATION_MODE
                        ),
                        DEFAULT_GENERATION_MODE
                ),
                asString(
                        firstMeaningful(
                                source.getOrDefault("model", DEFAULT_MODEL),
                                normalizedPipeline.get("openAiModel"),
                                DEFAULT_MODEL
                        ),
                        DEFAULT_MODEL
                ),
                asBoolean(
                        sourceGenerationModeMeaningful
                                ? source.getOrDefault("realApiUsed", false)
                                : normalizedPipeline.getOrDefault("openAiRealApiUsed", false)
                ),
                asString(
                        firstMeaningful(
                                source.getOrDefault("fallbackReason", DEFAULT_FALLBACK_REASON),
                                normalizedPipeline.get("openAiFallbackReason"),
                                DEFAULT_FALLBACK_REASON
                        ),
                        DEFAULT_FALLBACK_REASON
                ),
                asString(source.getOrDefault("overall", ""), ""),
                asStringList(source.getOrDefault("strengths", List.of())),
                asStringList(source.getOrDefault("improvements", List.of()))
        );
    }

    // 저장된 feedback 자체가 없을 때(예: finalResult가 비어있음) pipeline의 OpenAI
    // 메타데이터만으로 만든다(ResultSummaryResponse.createFeedbackFromPipeline()과 동일).
    public static FeedbackSummary fromPipeline(Map<String, Object> pipeline) {
        Map<String, Object> normalizedPipeline = pipeline == null ? Map.of() : pipeline;

        return new FeedbackSummary(
                asString(
                        firstMeaningful(
                                normalizedPipeline.get("openAiGenerationMode"),
                                DEFAULT_GENERATION_MODE,
                                DEFAULT_GENERATION_MODE
                        ),
                        DEFAULT_GENERATION_MODE
                ),
                asString(
                        firstMeaningful(
                                normalizedPipeline.get("openAiModel"),
                                DEFAULT_MODEL,
                                DEFAULT_MODEL
                        ),
                        DEFAULT_MODEL
                ),
                asBoolean(normalizedPipeline.getOrDefault("openAiRealApiUsed", false)),
                asString(
                        firstMeaningful(
                                normalizedPipeline.get("openAiFallbackReason"),
                                DEFAULT_FALLBACK_REASON,
                                DEFAULT_FALLBACK_REASON
                        ),
                        DEFAULT_FALLBACK_REASON
                ),
                "",
                List.of(),
                List.of()
        );
    }

    private static Object firstMeaningful(
            Object primaryValue,
            Object fallbackValue,
            Object defaultValue
    ) {
        if (isMeaningful(primaryValue)) {
            return primaryValue;
        }

        if (isMeaningful(fallbackValue)) {
            return fallbackValue;
        }

        return defaultValue;
    }

    private static boolean isMeaningful(Object value) {
        if (value == null) {
            return false;
        }

        String text = value.toString();
        return !text.isBlank() && !"-".equals(text) && !"UNKNOWN".equals(text);
    }

    private static String asString(Object value, String fallback) {
        return value instanceof String stringValue ? stringValue : fallback;
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean boolValue && boolValue;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        return list.stream()
                .filter(item -> item instanceof String)
                .map(String.class::cast)
                .toList();
    }
}
