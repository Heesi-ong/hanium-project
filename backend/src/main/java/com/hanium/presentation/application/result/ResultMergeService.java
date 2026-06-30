package com.hanium.presentation.application.result;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ResultMergeService {

    public Map<String, Object> createMockFinalResult(String jobId) {
        return Map.of(
                "jobId", jobId,
                "status", "COMPLETED",
                "createdAt", LocalDateTime.now().toString(),
                "score", Map.of(
                        "totalScore", 0,
                        "postureScore", 0,
                        "gazeScore", 0,
                        "speechScore", 0
                ),
                "feedback", Map.of(
                        "overall", "Mock 분석 결과입니다. 실제 분석 엔진 연결 전 테스트용 결과입니다.",
                        "strengths", List.of(),
                        "improvements", List.of(),
                        "practicePlan", List.of()
                ),
                "pipeline", Map.of(
                        "basicAnalysis", "mock",
                        "videoLlmAnalysis", "mock",
                        "openAiFeedback", "mock"
                )
        );
    }
}