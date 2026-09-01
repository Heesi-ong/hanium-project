package com.hanium.presentation.presentation.dto.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// scoreSummary가 Map<String,Object>로 즉석에서 만들어지고 있어(2026-08-03 서비스화 점검
// P2-04), 필드 누락이나 타입 변경을 컴파일 타임에 잡지 못한다. versioned DTO(ScoreSummary)로
// 옮기기 전에, 동작을 바꾸지 않는다는 전제로 현재 출력 shape을 먼저 고정한다. ScoreSummary로
// 옮긴 뒤에도 JSON 직렬화 결과는 그대로여야 하므로, 타입 자체뿐 아니라 ObjectMapper로 변환한
// JSON shape도 함께 검증한다.
class ResultSummaryResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizeFinalResultFillsInEmptyScoreSummaryWhenMissing() {
        Map<String, Object> normalized = ResultSummaryResponse.normalizeFinalResult(Map.of());

        assertThat(normalized.get("scoreSummary")).isEqualTo(ScoreSummary.empty());

        Map<String, Object> scoreSummaryJson = scoreSummaryAsJsonMap(normalized);
        assertThat(scoreSummaryJson.keySet()).containsExactlyInAnyOrder(
                "totalScore",
                "postureScore",
                "speechScore",
                "gestureScore",
                "level"
        );
        assertThat(scoreSummaryJson)
                .containsEntry("totalScore", 0)
                .containsEntry("postureScore", 0)
                .containsEntry("speechScore", 0)
                .containsEntry("gestureScore", 0)
                .containsEntry("level", "-");
    }

    @Test
    void normalizeFinalResultPassesThroughAnExistingScoreSummary() {
        Map<String, Object> finalResult = Map.of(
                "scoreSummary", Map.of(
                        "totalScore", 91,
                        "postureScore", 88,
                        "gazeScore", 90,
                        "speechScore", 95,
                        "gestureScore", 89,
                        "expressionScore", 93,
                        "level", "EXCELLENT"
                )
        );

        Map<String, Object> normalized = ResultSummaryResponse.normalizeFinalResult(finalResult);

        assertThat(normalized.get("scoreSummary"))
                .isEqualTo(new ScoreSummary(91, 88, 95, 89, "EXCELLENT"));

        Map<String, Object> scoreSummaryJson = scoreSummaryAsJsonMap(normalized);
        assertThat(scoreSummaryJson)
                .containsEntry("totalScore", 91)
                .containsEntry("level", "EXCELLENT");
    }

    @Test
    void normalizeFinalResultPreservesAnExistingTypedFeedbackSummary() {
        FeedbackSummary feedback = new FeedbackSummary(
                "REAL",
                "gpt-4.1-mini",
                true,
                "-",
                "잘하셨습니다.",
                List.of("자세가 안정적입니다."),
                List.of("시선을 더 유지하세요.")
        );
        Map<String, Object> finalResult = Map.of(
                "feedback", feedback,
                "pipeline", Map.of("openAiGenerationMode", "REAL")
        );

        Map<String, Object> normalized = ResultSummaryResponse.normalizeFinalResult(finalResult);

        assertThat(normalized.get("feedback")).isSameAs(feedback);
    }

    @Test
    void normalizeFinalResultKeepsFeedbackWhenNormalizedTwice() {
        Map<String, Object> finalResult = Map.of(
                "feedback", Map.of(
                        "generationMode", "REAL",
                        "model", "gpt-4.1-mini",
                        "realApiUsed", true,
                        "fallbackReason", "-",
                        "overall", "잘하셨습니다.",
                        "strengths", List.of("자세가 안정적입니다."),
                        "improvements", List.of("시선을 더 유지하세요.")
                ),
                "pipeline", Map.of("openAiGenerationMode", "REAL")
        );

        Map<String, Object> once = ResultSummaryResponse.normalizeFinalResult(finalResult);
        Map<String, Object> twice = ResultSummaryResponse.normalizeFinalResult(once);

        assertThat(twice.get("feedback")).isEqualTo(once.get("feedback"));
    }

    @Test
    void resolveDataIssueFlagsPlaceholderLevelAsIncomplete() {
        ScoreSummary scoreSummary = scoreSummaryWithLevel("-");
        FeedbackSummary feedback = feedbackWith("REAL", "잘하셨습니다.");

        assertThat(ResultSummaryResponse.resolveDataIssue(scoreSummary, feedback))
                .isEqualTo("RESULT_DATA_INCOMPLETE");
    }

    @Test
    void resolveDataIssueFlagsUnknownGenerationModeAsIncomplete() {
        ScoreSummary scoreSummary = scoreSummaryWithLevel("GOOD");
        FeedbackSummary feedback = feedbackWith("UNKNOWN", "잘하셨습니다.");

        assertThat(ResultSummaryResponse.resolveDataIssue(scoreSummary, feedback))
                .isEqualTo("RESULT_DATA_INCOMPLETE");
    }

    @Test
    void resolveDataIssueFlagsBlankOverallAsIncomplete() {
        ScoreSummary scoreSummary = scoreSummaryWithLevel("GOOD");
        FeedbackSummary feedback = feedbackWith("REAL", "");

        assertThat(ResultSummaryResponse.resolveDataIssue(scoreSummary, feedback))
                .isEqualTo("RESULT_DATA_INCOMPLETE");
    }

    @Test
    void resolveDataIssueReturnsNullWhenResultLooksComplete() {
        ScoreSummary scoreSummary = scoreSummaryWithLevel("GOOD");
        FeedbackSummary feedback = feedbackWith("REAL", "잘하셨습니다.");

        assertThat(ResultSummaryResponse.resolveDataIssue(scoreSummary, feedback)).isNull();
    }

    @Test
    void missingVideoUsesPlaceholderVideoFieldsAndPreservesPipeline() {
        Map<String, Object> finalResult = Map.of(
                "pipeline", Map.of("openAiGenerationMode", "REAL", "openAiModel", "gpt-4.1-mini")
        );

        ResultSummaryResponse response = ResultSummaryResponse.missingVideo(
                stubAnalysisJob(),
                finalResult
        );

        assertThat(response.originalFileName()).isEqualTo("(영상 정보 없음)");
        assertThat(response.fileSize()).isZero();
        assertThat(response.pipeline()).containsEntry("openAiModel", "gpt-4.1-mini");
    }

    private ScoreSummary scoreSummaryWithLevel(String level) {
        return new ScoreSummary(0, 0, 0, 0, level);
    }

    private FeedbackSummary feedbackWith(String generationMode, String overall) {
        return new FeedbackSummary(generationMode, "-", false, "-", overall, List.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scoreSummaryAsJsonMap(Map<String, Object> normalized) {
        return objectMapper.convertValue(normalized.get("scoreSummary"), Map.class);
    }

    private com.hanium.presentation.domain.analysis.entity.AnalysisJob stubAnalysisJob() {
        return com.hanium.presentation.domain.analysis.entity.AnalysisJob.create("job-1", 1L);
    }
}
