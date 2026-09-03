package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** 분석 파이프라인이 생성한 중간·최종·종료 결과의 저장 경계입니다. */
final class AnalysisResultPersistenceStage {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultPersistenceStage.class);
    private static final String CANCELLED_REASON = "사용자 요청으로 분석 작업이 취소되었습니다.";

    private final ResultCommandService resultCommandService;

    AnalysisResultPersistenceStage(ResultCommandService resultCommandService) {
        this.resultCommandService = resultCommandService;
    }

    /**
     * 기본 분석 응답의 스켈레톤 오버레이 프레임을 결과 스토리지에 저장하고, 큰 base64를
     * 비운 응답 사본을 돌려줍니다. 이후 단계는 가벼워진 응답만 들고 다닙니다.
     */
    AnalysisEngineResponse persistFrameOverlays(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse
    ) {
        return resultCommandService.persistFrameOverlays(jobId, analysisEngineResponse);
    }

    Map<String, Object> compact(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        return resultCommandService.saveEngineResultsAndCompact(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse
        );
    }

    void saveFinal(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        resultCommandService.saveFinalResult(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse,
                openAiFeedbackResponse
        );
    }

    void saveFailureSafely(String jobId, String failReason) {
        saveTerminalResultSafely(jobId, AnalysisStatus.FAILED, failReason);
    }

    void saveCancelledSafely(String jobId) {
        saveTerminalResultSafely(jobId, AnalysisStatus.CANCELLED, CANCELLED_REASON);
    }

    private void saveTerminalResultSafely(
            String jobId,
            AnalysisStatus terminalStatus,
            String reason
    ) {
        try {
            resultCommandService.saveFailureResult(jobId, terminalStatus.name(), reason);
        } catch (Exception exception) {
            // 종료 결과 저장 오류가 원래 실패/취소 상태를 덮어쓰지 않도록 격리하되 운영 추적은 남깁니다.
            log.warn(
                    "[{}] {} 종료 결과 저장에 실패했습니다. 원래 종료 상태를 유지합니다.",
                    jobId,
                    terminalStatus,
                    exception
            );
        }
    }
}
