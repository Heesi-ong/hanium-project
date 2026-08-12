package com.hanium.presentation.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

// 결과 계약의 scoreSummary가 그동안 Map<String,Object>로 즉석에서 만들어지고 읽혀,
// 필드 누락이나 타입 변경을 컴파일 타임에 잡지 못했다(2026-08-03 서비스화 점검 P2-04).
// 가장 안정적인 top-level 필드인 scoreSummary부터 versioned DTO로 옮기는 첫 단계다.
// JSON 직렬화 결과(필드 이름/구조)는 기존 Map과 동일하게 유지해 API 계약과 프론트는
// 변경이 필요 없다. 과거에 저장된 결과 JSON은 디스크에서 다시 읽을 때 항상
// Map<String,Object>로 역직렬화되므로(JsonFileStorage.readObjectMap), from()이 그
// 과거 shape을 그대로 관용적으로 받아들인다 — 필드가 없거나 타입이 다르면 기본값으로
// 채운다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoreSummary(
        int totalScore,
        int postureScore,
        int gazeScore,
        int speechScore,
        int gestureScore,
        int expressionScore,
        String level
) {

    private static final String DEFAULT_LEVEL = "-";

    public static ScoreSummary empty() {
        return new ScoreSummary(0, 0, 0, 0, 0, 0, DEFAULT_LEVEL);
    }

    public static ScoreSummary failed() {
        return new ScoreSummary(0, 0, 0, 0, 0, 0, "FAILED");
    }

    // 과거 결과 JSON(디스크에서 다시 읽은 raw Map)이나 아직 실제 값이 확인되지 않은
    // 입력을 관용적으로 받아들인다. 필드가 없거나 숫자가 아니면 0으로, level이 없으면
    // "-"로 채운다.
    public static ScoreSummary from(Map<?, ?> raw) {
        if (raw == null) {
            return empty();
        }

        return new ScoreSummary(
                intValue(raw.get("totalScore")),
                intValue(raw.get("postureScore")),
                intValue(raw.get("gazeScore")),
                intValue(raw.get("speechScore")),
                intValue(raw.get("gestureScore")),
                intValue(raw.get("expressionScore")),
                raw.get("level") instanceof String level ? level : DEFAULT_LEVEL
        );
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        return 0;
    }
}
