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
