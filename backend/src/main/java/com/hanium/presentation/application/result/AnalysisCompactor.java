package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AnalysisCompactor {

    public Map<String, Object> compact(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> compactResult = new LinkedHashMap<>();

        compactResult.put("jobId", jobId);
        compactResult.put("createdAt", LocalDateTime.now().toString());
        compactResult.put("videoSummary", createVideoSummary(analysisEngineResponse));
        compactResult.put("scoreSummary", createScoreSummary(analysisEngineResponse));
        compactResult.put("visualSummary", createVisualSummary(
                analysisEngineResponse,
                videoLlmEngineResponse
        ));
        compactResult.put("compactPurpose", "OpenAI 최종 피드백 생성을 위한 축약 분석 데이터");

        return compactResult;
    }

    private Map<String, Object> createVideoSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> videoSummary = new LinkedHashMap<>();

        videoSummary.put("videoInfo", nullSafeMap(analysisEngineResponse.videoInfo()));
        videoSummary.put("frame", nullSafeMap(analysisEngineResponse.frame()));

        return videoSummary;
    }

    private Map<String, Object> createScoreSummary(
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Map<String, Object> scoreSummary = new LinkedHashMap<>();

        scoreSummary.put("score", nullSafeMap(analysisEngineResponse.score()));
        scoreSummary.put("audio", nullSafeMap(analysisEngineResponse.audio()));
        scoreSummary.put("filler", nullSafeMap(analysisEngineResponse.filler()));

        return scoreSummary;
    }

    private Map<String, Object> createVisualSummary(
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> visualSummary = new LinkedHashMap<>();

        visualSummary.put("pose", nullSafeMap(analysisEngineResponse.pose()));
        visualSummary.put("gesture", nullSafeMap(analysisEngineResponse.gesture()));
        visualSummary.put("face", nullSafeMap(analysisEngineResponse.face()));
        visualSummary.put("emotion", nullSafeMap(analysisEngineResponse.emotion()));
        visualSummary.put("observations", nullSafeObject(videoLlmEngineResponse.observations()));
        visualSummary.put("globalSummary", nullSafeMap(videoLlmEngineResponse.globalSummary()));

        return visualSummary;
    }

    private Map<String, Object> nullSafeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private Object nullSafeObject(Object value) {
        return value == null ? Map.of() : value;
    }
}