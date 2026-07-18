package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultMergeServiceTest {

    private final ResultMergeService resultMergeService = new ResultMergeService();

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

        assertThat(notableMoments).hasSize(4);
        assertThat(findMoment(notableMoments, "posture"))
                .containsEntry("label", "자세 균형이 가장 흔들린 순간")
                .containsEntry("timestampSec", 2.5)
                .containsEntry("value", 37.0);
        assertThat(findMoment(notableMoments, "gaze"))
                .containsEntry("label", "카메라 응시가 가장 흔들린 순간")
                .containsEntry("timestampSec", 4.0)
                .containsEntry("value", 24.0);
        assertThat(findMoment(notableMoments, "expression"))
                .containsEntry("label", "표정 표현이 가장 약했던 순간")
                .containsEntry("timestampSec", 6.5)
                .containsEntry("value", 18.0);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> notableMoments(Map<String, Object> finalResult) {
        return (List<Map<String, Object>>) finalResult.get("notableMoments");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> visualAnalysis(Map<String, Object> finalResult) {
        return (Map<String, Object>) finalResult.get("visualAnalysis");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pipeline(Map<String, Object> finalResult) {
        return (Map<String, Object>) finalResult.get("pipeline");
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
                        "expressionScore", 80
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
