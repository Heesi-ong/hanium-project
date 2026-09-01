package com.hanium.presentation.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hanium.presentation.common.util.JsonMapSupport;

import java.util.List;
import java.util.Map;

/** 분석 점수의 신뢰도와 감점 근거를 프론트에 안정적으로 전달하는 결과 계약입니다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisQualitySummary(
        boolean available,
        boolean lowConfidence,
        Double poseDetectionRate,
        String audioAnalysisMethod,
        boolean sttFallbackUsed,
        int penaltyApplied,
        List<String> penaltyReasons,
        String formulaVersion
) {

    public static AnalysisQualitySummary fromEngine(
            Map<String, Object> score,
            Map<String, Object> audio
    ) {
        Map<String, Object> normalizedScore = score == null ? Map.of() : score;
        Map<String, Object> normalizedAudio = audio == null ? Map.of() : audio;
        Map<String, Object> reliability = map(normalizedScore.get("reliability"));
        Map<String, Object> explanation = map(normalizedScore.get("explanation"));
        String audioMethod = stringValue(normalizedAudio.get("analysisMethod"), "UNKNOWN");

        return new AnalysisQualitySummary(
                !reliability.isEmpty() || !explanation.isEmpty(),
                booleanValue(reliability.get("lowConfidence")),
                doubleValue(reliability.get("poseDetectionRate")),
                audioMethod,
                !"UNKNOWN".equals(audioMethod) && !"stt_based_analysis".equals(audioMethod),
                intValue(explanation.get("penaltyApplied")),
                stringList(reliability.getOrDefault(
                        "penaltyReasons",
                        explanation.getOrDefault("penaltyReasons", List.of())
                )),
                stringValue(explanation.get("formulaVersion"), "UNKNOWN")
        );
    }

    public static AnalysisQualitySummary from(Map<?, ?> raw) {
        if (raw == null) {
            return unavailable();
        }

        Map<String, Object> source = JsonMapSupport.copyStringKeyedMap(raw);
        return new AnalysisQualitySummary(
                booleanValue(source.get("available")),
                booleanValue(source.get("lowConfidence")),
                doubleValue(source.get("poseDetectionRate")),
                stringValue(source.get("audioAnalysisMethod"), "UNKNOWN"),
                booleanValue(source.get("sttFallbackUsed")),
                intValue(source.get("penaltyApplied")),
                stringList(source.get("penaltyReasons")),
                stringValue(source.get("formulaVersion"), "UNKNOWN")
        );
    }

    public static AnalysisQualitySummary unavailable() {
        return new AnalysisQualitySummary(
                false,
                false,
                null,
                "UNKNOWN",
                false,
                0,
                List.of(),
                "UNKNOWN"
        );
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? JsonMapSupport.copyStringKeyedMap(value) : Map.of();
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private static Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
