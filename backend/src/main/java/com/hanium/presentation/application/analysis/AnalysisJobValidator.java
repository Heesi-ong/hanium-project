package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisRetryProperties;
import org.springframework.stereotype.Component;

// AnalysisCommandService(1000줄 이상)에서 상태 전이 검증만 분리했다(2026-08-03 서비스화
// 점검 P2-02). 순수 검증 로직이라 외부 I/O가 없고, 이미 여러 통합 테스트(Analysis*
// IntegrationTest 계열)가 HTTP 계약을 통해 이 동작을 간접적으로 특징화하고 있어 분리
// 위험이 낮다. 동작은 바꾸지 않았다 — AnalysisCommandService에 있던 세 private 메서드를
// 그대로 옮겼을 뿐이다.
@Component
public class AnalysisJobValidator {

    private final AnalysisRetryProperties analysisRetryProperties;

    public AnalysisJobValidator(AnalysisRetryProperties analysisRetryProperties) {
        this.analysisRetryProperties = analysisRetryProperties;
    }

    public void validateRunnable(AnalysisJob analysisJob) {
        if (analysisJob.isRunning() || analysisJob.isQueued()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "이미 실행이 접수되었거나 진행 중인 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_COMPLETED,
                    "이미 완료된 분석 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (!analysisJob.canRun()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "현재 상태에서는 분석을 실행할 수 없습니다. status=" + analysisJob.getStatus()
            );
        }
    }

    public void validateRetryable(AnalysisJob analysisJob) {
        if (analysisJob.isRunning() || analysisJob.isQueued()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "이미 실행이 접수되었거나 진행 중인 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_COMPLETED,
                    "이미 완료된 분석 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        // DEAD_LETTER는 canRetry()에서 이미 제외되지만, 아래의 일반 "실패/취소 상태만
        // 재시도 가능" 메시지 대신 기존과 동일한 ANALYSIS_RETRY_LIMIT_EXCEEDED(409)를
        // 반환합니다. DEAD_LETTER는 정의상 재시도 한도를 소진한 상태이므로, 사용자에게는
        // 이 API 계약을 유지하면서 관리자 재처리가 필요하다는 것만 안내하면 충분합니다.
        if (analysisJob.isDeadLetter()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_RETRY_LIMIT_EXCEEDED,
                    "재시도 가능 횟수를 모두 소진했습니다. 관리자 재처리가 필요합니다. jobId="
                            + analysisJob.getJobId()
            );
        }

        if (!analysisJob.canRetry()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "실패 또는 취소 상태의 분석 작업만 재시도할 수 있습니다. status=" + analysisJob.getStatus()
            );
        }

        if (analysisJob.getRetryCount() >= analysisRetryProperties.maxCount()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_RETRY_LIMIT_EXCEEDED,
                    "재시도 가능 횟수를 초과했습니다. jobId=" + analysisJob.getJobId()
                            + ", retryCount=" + analysisJob.getRetryCount()
                            + ", maxRetryCount=" + analysisRetryProperties.maxCount()
            );
        }
    }

    public void validateOwnership(AnalysisJob analysisJob, Long ownerId) {
        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }
    }
}
