package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AnalysisCompactor {

    public Map<String, Object> compact(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        return Map.of(
                "jobId", jobId,
                "createdAt", LocalDateTime.now().toString(),

                "videoSummary", Map.of(
                        "videoInfo", nullSafe(analysisEngineResponse.videoInfo()),
                        "frame", nullSafe(analysisEngineResponse.frame())
                ),

                "scoreSummary", Map.of(
                        "score", nullSafe(analysisEngineResponse.score()),
                        "audio", nullSafe(analysisEngineResponse.audio()),
                        "filler", nullSafe(analysisEngineResponse.filler())
                ),

                "visualSummary", Map.of(
                        "pose", nullSafe(analysisEngineResponse.pose()),
                        "gesture", nullSafe(analysisEngineResponse.gesture()),
                        "face", nullSafe(analysisEngineResponse.face()),
                        "observations", nullSafe(videoLlmEngineResponse.observations()),
                        "globalSummary", nullSafe(videoLlmEngineResponse.globalSummary())
                ),

                "compactPurpose", "OpenAI 최종 피드백 생성을 위한 축약 분석 데이터"
        );
    }

    private Map<String, Object> nullSafe(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}