package com.hanium.presentation.application.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.common.contract.ResultSchemaVersion;
import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultMergeServiceTest {

    private final ResultMergeService resultMergeService = new ResultMergeService();
    // scoreSummary는 이제 ScoreSummary 레코드 타입이라(P2-04), Map 기반 필드처럼 바로
    // 캐스팅할 수 없다. ObjectMapper로 실제 JSON 직렬화 shape과 동일한 Map으로 변환해
    // 검증한다 — 이렇게 하면 "JSON 계약은 그대로다"까지 함께 검증된다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createFinalResultAddsDeterministicNotableMomentsFromFrameResults() {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(
                        Map.of(
                                "frameResults",
                                List.of(
                                        Map.of(
                                                "timestampSec", 0.5,
                                                "poseDetected", false,
                                                "shoulderBalanceScore", 1
                                        ),
                                        Map.of(
                                                "timestampSec", 1.0,
                                                "poseDetected", true,
                                                "shoulderBalanceScore", 82
                                        ),
                                        Map.of(
                                                "timestampSec", 2.5,
                                                "poseDetected", true,
                                                "shoulderBalanceScore", 37
                                        )
                                )
                        ),
                        Map.of(
                                "frameResults",
                                List.of(
                                        Map.of(
                                                "timestampSec", 3.0,
                                                "faceDetected", true,
                                                "gazeScore", 91
                                        ),
                                        Map.of(
                                                "timestampSec", 4.0,
                                                "faceDetected", true,
                                                "gazeScore", 24
                                        )
                                )
                        ),
                        Map.of(
                                "frameResults",
                                List.of(
                                        Map.of(
                                                "timestampSec", 5.0,
                                                "faceDetected", true,
                                                "expressionScore", 72
                                        ),
                                        Map.of(
                                                "timestampSec", 6.5,
                                                "faceDetected", true,
                                                "expressionScore", 18
                                        )
                                )
                        ),
                        Map.of(
                                "frameResults",
                                List.of(
                                        Map.of(
                                                "timestampSec", 7.0,
                                                "gestureDetected", false,
                                                "leftWristMovement", 99,
                                                "rightWristMovement", 99
                                        ),
                                        Map.of(
                                                "timestampSec", 8.0,
                                                "gestureDetected", true,
                                                "leftWristMovement", 1,
                                                "rightWristMovement", 2
                                        ),
                                        Map.of(
                                                "timestampSec", 9.5,
                                                "gestureDetected", true,
                                                "leftWristMovement", 7
                                        )
                                )
                        )
                ),
                videoLlmResponse(),
                openAiFeedbackResponse()
        );

        List<Map<String, Object>> notableMoments = notableMoments(finalResult);

        assertThat(finalResult)
                .containsEntry(ResultSchemaVersion.FIELD, ResultSchemaVersion.CURRENT);
        assertThat(notableMoments).hasSize(2);
        assertThat(findMoment(notableMoments, "posture"))
                .containsEntry("label", "자세 균형이 가장 흔들린 순간")
                .containsEntry("timestampSec", 2.5)
                .containsEntry("value", 37.0);
        assertThat(findMoment(notableMoments, "gesture"))
                .containsEntry("label", "제스처가 가장 활발했던 순간")
                .containsEntry("timestampSec", 9.5)
                .containsEntry("value", 7.0);
    }

    @Test
    void createFinalResultOmitsCategoriesWithoutValidCandidateFrames() {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(
                        Map.of(
                                "frameResults",
                                List.of(
                                        Map.of(
                                                "timestampSec", 1.0,
                                                "poseDetected", false,
                                                "shoulderBalanceScore", 1
                                        )
                                )
                        ),
                        Map.of(),
                        Map.of("frameResults", List.of()),
                        Map.of(
                                "frameResults",
                                List.of(
                                        Map.of(
                                                "gestureDetected", true,
                                                "leftWristMovement", 20
                                        )
                                )
                        )
                ),
                videoLlmResponse(),
                openAiFeedbackResponse()
        );

        assertThat(notableMoments(finalResult)).isEmpty();
    }

    @Test
    void createFailureResultKeepsNotableMomentsAsEmptyList() {
        Map<String, Object> failureResult = resultMergeService.createFailureResult(
                "job-1",
                "BASIC_ANALYZING",
                "analysis failed"
        );

        assertThat(failureResult).containsEntry("notableMoments", List.of());
    }

    // scoreSummary는 Map<String,Object>로 즉석에서 만들어지고 있어(2026-08-03 서비스화 점검
    // P2-04), 필드 누락이나 타입 변경을 컴파일 타임에 잡지 못한다. versioned DTO로 옮기기
    // 전에, 동작을 바꾸지 않는다는 전제로 현재 출력 shape을 먼저 고정한다(P2-02와 동일한
    // characterization test 우선 원칙).
    @Test
    void createFinalResultProducesScoreSummaryWithCurrentShapeAndValues() {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(Map.of(), Map.of(), Map.of(), Map.of()),
                videoLlmResponse(),
                openAiFeedbackResponse()
        );

        Map<String, Object> scoreSummary = scoreSummary(finalResult);

        assertThat(scoreSummary.keySet()).containsExactlyInAnyOrder(
                "totalScore",
                "postureScore",
                "speechScore",
                "gestureScore",
                "level"
        );
        assertThat(scoreSummary)
                .containsEntry("totalScore", 80)
                .containsEntry("postureScore", 80)
                .containsEntry("speechScore", 80)
                .containsEntry("gestureScore", 80)
                .containsEntry("level", "GOOD");
    }

    @Test
    void createFinalResultPreservesScoreExplanationWithoutChangingScoreSummary() {
        Map<String, Object> explanation = Map.of(
                "formulaVersion", "weighted-v1",
                "roundingPolicy", "truncate_toward_zero",
                "rawScore", 80,
                "penaltyApplied", 0
        );
        AnalysisEngineResponse response = analysisResponse(
                Map.of(), Map.of(), Map.of(), Map.of(), explanation
        );

        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                response,
                videoLlmResponse(),
                openAiFeedbackResponse()
        );

        assertThat(finalResult.get("scoreExplanation")).isEqualTo(explanation);
        assertThat(scoreSummary(finalResult)).containsEntry("totalScore", 80);
    }

    @Test
    void createFinalResultAddsTypedAnalysisQualityWithoutChangingScores() {
        Map<String, Object> explanation = Map.of(
                "formulaVersion", "weighted-v1",
                "penaltyApplied", 8,
                "penaltyReasons", List.of("얼굴 검출률이 50% 미만입니다.")
        );
        AnalysisEngineResponse base = analysisResponse(
                Map.of(), Map.of(), Map.of(), Map.of(), explanation
        );
        Map<String, Object> score = new java.util.LinkedHashMap<>(base.score());
        score.put("reliability", Map.of(
                "lowConfidence", true,
                "poseDetectionRate", 0.72,
                "faceDetectionRate", 0.41,
                "penaltyReasons", List.of("얼굴 검출률이 50% 미만입니다.")
        ));
        AnalysisEngineResponse response = new AnalysisEngineResponse(
                base.jobId(),
                base.status(),
                base.videoInfo(),
                base.frame(),
                Map.of("analysisMethod", "signal_estimation"),
                base.filler(),
                base.pose(),
                base.gesture(),
                base.face(),
                base.emotion(),
                score,
                base.error()
        );

        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1", response, videoLlmResponse(), openAiFeedbackResponse()
        );

        Map<String, Object> quality = objectMapper.convertValue(
                finalResult.get("analysisQuality"),
                Map.class
        );
        assertThat(quality)
                .containsEntry("available", true)
                .containsEntry("lowConfidence", true)
                .containsEntry("poseDetectionRate", 0.72)
                .containsEntry("audioAnalysisMethod", "signal_estimation")
                .containsEntry("sttFallbackUsed", true)
                .containsEntry("penaltyApplied", 8)
                .containsEntry("formulaVersion", "weighted-v1");
        assertThat(scoreSummary(finalResult)).containsEntry("totalScore", 80);
    }

    @Test
    void createFailureResultProducesScoreSummaryWithCurrentShapeAndFailedDefaults() {
        Map<String, Object> failureResult = resultMergeService.createFailureResult(
                "job-1",
                "BASIC_ANALYZING",
                "analysis failed"
        );

        Map<String, Object> scoreSummary = scoreSummary(failureResult);

        assertThat(failureResult)
                .containsEntry(ResultSchemaVersion.FIELD, ResultSchemaVersion.CURRENT);
        assertThat(scoreSummary.keySet()).containsExactlyInAnyOrder(
                "totalScore",
                "postureScore",
                "speechScore",
                "gestureScore",
                "level"
        );
        assertThat(scoreSummary)
                .containsEntry("totalScore", 0)
                .containsEntry("postureScore", 0)
                .containsEntry("speechScore", 0)
                .containsEntry("gestureScore", 0)
                .containsEntry("level", "FAILED");
    }

    @Test
    void createFinalResultIncludesVideoLlmStatusInVisualAnalysis() {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(Map.of(), Map.of(), Map.of(), Map.of()),
                new VideoLlmEngineResponse(
                        "job-1",
                        "skipped",
                        Map.of("generationMode", "SKIPPED"),
                        Map.of(),
                        Map.of("visualDelivery", "Video LLM 분석 생략")
                ),
                openAiFeedbackResponse()
        );

        assertThat(visualAnalysis(finalResult)).containsEntry("status", "skipped");
    }

    @Test
    void createFinalResultReflectsVideoLlmGenerationModeInPipeline() {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(Map.of(), Map.of(), Map.of(), Map.of()),
                new VideoLlmEngineResponse(
                        "job-1",
                        "completed",
                        Map.of("generationMode", "FALLBACK"),
                        Map.of(),
                        Map.of()
                ),
                openAiFeedbackResponse()
        );

        assertThat(pipeline(finalResult))
                .containsEntry("videoLlmAnalysis", "video-llm-engine fallback mock")
                .containsEntry("videoLlmGenerationMode", "FALLBACK");
    }

    @Test
    void createFinalResultMarksVideoLlmPipelineAsSkippedWhenVideoLlmWasSkipped() {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(Map.of(), Map.of(), Map.of(), Map.of()),
                new VideoLlmEngineResponse(
                        "job-1",
                        "skipped",
                        Map.of("generationMode", "SKIPPED"),
                        Map.of(),
                        Map.of()
                ),
                openAiFeedbackResponse()
        );

        assertThat(pipeline(finalResult))
                .containsEntry("videoLlmAnalysis", "video-llm-engine skipped")
                .containsEntry("videoLlmGenerationMode", "SKIPPED");
    }

    @Test
    void createFinalResultMarksOpenAiPipelineAsSkippedWhenFeedbackWasDisabled() {
        OpenAiFeedbackResponse skippedFeedback = OpenAiFeedbackResponse.skipped(
                "job-1",
                "사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다.",
                "기본 분석 결과만 저장되었습니다.",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1",
                analysisResponse(Map.of(), Map.of(), Map.of(), Map.of()),
                videoLlmResponse(),
                skippedFeedback
        );

        assertThat(pipeline(finalResult))
                .containsEntry("openAiFeedback", "openai skipped")
                .containsEntry("openAiGenerationMode", "SKIPPED")
                .containsEntry("openAiRealApiUsed", false)
                .containsEntry(
                        "openAiFallbackReason",
                        "사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다."
                );
    }

    private List<Map<String, Object>> notableMoments(Map<String, Object> finalResult) {
        return JsonMapSupport.copyStringKeyedMapList(finalResult.get("notableMoments"));
    }

    private Map<String, Object> visualAnalysis(Map<String, Object> finalResult) {
        return JsonMapSupport.copyStringKeyedMap(finalResult.get("visualAnalysis"));
    }

    private Map<String, Object> pipeline(Map<String, Object> finalResult) {
        return JsonMapSupport.copyStringKeyedMap(finalResult.get("pipeline"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scoreSummary(Map<String, Object> finalResult) {
        return objectMapper.convertValue(finalResult.get("scoreSummary"), Map.class);
    }

    private Map<String, Object> findMoment(
            List<Map<String, Object>> notableMoments,
            String category
    ) {
        return notableMoments.stream()
                .filter(moment -> category.equals(moment.get("category")))
                .findFirst()
                .orElseThrow();
    }

    private AnalysisEngineResponse analysisResponse(
            Map<String, Object> pose,
            Map<String, Object> face,
            Map<String, Object> emotion,
            Map<String, Object> gesture
    ) {
        return analysisResponse(pose, face, emotion, gesture, Map.of());
    }

    @Test
    void createFinalResultPassesThroughAnalysisTraceAndFrameGallery() {
        AnalysisEngineResponse base = analysisResponse(Map.of(), Map.of(), Map.of(), Map.of());
        List<Map<String, Object>> trace = List.of(Map.of(
                "stepNo", 5, "totalSteps", 9, "key", "pose_gesture",
                "label", "자세와 제스처를 분석하는 중...", "durationMs", 42.0
        ));
        List<Map<String, Object>> gallery = List.of(Map.of(
                "sequence", 1, "timestampSec", 1.0, "poseDetected", true,
                "gestureDetected", false, "fileName", "frame_001.jpg"
        ));
        AnalysisEngineResponse response = new AnalysisEngineResponse(
                base.jobId(), base.status(), trace, List.of(), gallery,
                base.videoInfo(), base.frame(), base.audio(), base.filler(),
                base.pose(), base.gesture(), base.face(), base.emotion(),
                base.score(), base.error()
        );

        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                "job-1", response, videoLlmResponse(), openAiFeedbackResponse()
        );

        Map<String, Object> basicAnalysis = objectMapper.convertValue(
                finalResult.get("basicAnalysis"), Map.class
        );
        assertThat((List<?>) basicAnalysis.get("analysisTrace")).hasSize(1);
        assertThat((List<?>) basicAnalysis.get("frameGallery")).hasSize(1);
        assertThat(objectMapper.convertValue(
                ((List<?>) basicAnalysis.get("frameGallery")).get(0), Map.class
        )).containsEntry("fileName", "frame_001.jpg");
    }

    @Test
    void createFailureResultStillCarriesEmptyTraceAndGallery() {
        Map<String, Object> failureResult = resultMergeService.createFailureResult(
                "job-1", "BASIC_ANALYZING", "analysis failed"
        );

        Map<String, Object> basicAnalysis = objectMapper.convertValue(
                failureResult.get("basicAnalysis"), Map.class
        );
        assertThat((List<?>) basicAnalysis.get("analysisTrace")).isEmpty();
        assertThat((List<?>) basicAnalysis.get("frameGallery")).isEmpty();
    }

    private AnalysisEngineResponse analysisResponse(
            Map<String, Object> pose,
            Map<String, Object> face,
            Map<String, Object> emotion,
            Map<String, Object> gesture,
            Map<String, Object> explanation
    ) {
        return new AnalysisEngineResponse(
                "job-1",
                "completed",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                pose,
                gesture,
                face,
                emotion,
                Map.of(
                        "totalScore", 80,
                        "postureScore", 80,
                        "gazeScore", 80,
                        "speechScore", 80,
                        "gestureScore", 80,
                        "expressionScore", 80,
                        "explanation", explanation
                ),
                Map.of()
        );
    }

    private VideoLlmEngineResponse videoLlmResponse() {
        return new VideoLlmEngineResponse(
                "job-1",
                "completed",
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private OpenAiFeedbackResponse openAiFeedbackResponse() {
        return OpenAiFeedbackResponse.mock(
                "job-1",
                "mock",
                "test",
                "overall",
                List.of("strength"),
                List.of("improvement"),
                List.of(),
                List.of()
        );
    }
}
