package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.AnalysisQueueProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 분석 접수 전에 DB 대기열과 로컬 executor 용량을 검사하는 정책 경계입니다.
 * 전역 DB 한도, 사용자별 DB 한도, 로컬 executor 순서로 판정합니다.
 */
final class AnalysisDispatchAdmissionPolicy {

    private final AnalysisJobRepository analysisJobRepository;
    private final ThreadPoolTaskExecutor analysisTaskExecutor;
    private final AnalysisQueueProperties analysisQueueProperties;
    private final MeterRegistry meterRegistry;

    AnalysisDispatchAdmissionPolicy(
            AnalysisJobRepository analysisJobRepository,
            ThreadPoolTaskExecutor analysisTaskExecutor,
            AnalysisQueueProperties analysisQueueProperties,
            MeterRegistry meterRegistry
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.analysisQueueProperties = analysisQueueProperties;
        this.meterRegistry = meterRegistry;
    }

    void verify(Long ownerId, boolean localDispatchOnRun) {
        rejectIfDatabaseQueueLimitExceeded(ownerId);
        if (localDispatchOnRun) {
            rejectIfLocalExecutorSaturated();
        }
    }

    private void rejectIfDatabaseQueueLimitExceeded(Long ownerId) {
        long globalQueuedCount = analysisJobRepository.countByStatus(AnalysisStatus.QUEUED);

        if (globalQueuedCount >= analysisQueueProperties.maxGlobalQueued()) {
            meterRegistry.counter("analysis.job.rejected", "reason", "queue-full-global").increment();
            throw new BusinessException(
                    ErrorCode.ANALYSIS_QUEUE_FULL,
                    "현재 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."
            );
        }

        long ownerQueuedCount = analysisJobRepository.countByStatusAndOwnerId(
                AnalysisStatus.QUEUED,
                ownerId
        );

        if (ownerQueuedCount >= analysisQueueProperties.maxQueuedPerUser()) {
            meterRegistry.counter("analysis.job.rejected", "reason", "queue-full-per-user").increment();
            throw new BusinessException(
                    ErrorCode.ANALYSIS_QUEUE_FULL,
                    "대기 중인 분석 작업이 너무 많습니다. 이전 작업이 끝난 뒤 다시 시도해주세요. (최대 "
                            + analysisQueueProperties.maxQueuedPerUser() + "건)"
            );
        }
    }

    private void rejectIfLocalExecutorSaturated() {
        ThreadPoolExecutor executor;
        try {
            executor = analysisTaskExecutor.getThreadPoolExecutor();
        } catch (IllegalStateException notInitialized) {
            return;
        }

        if (executor == null) {
            return;
        }

        boolean saturated = executor.getQueue().remainingCapacity() == 0
                && executor.getActiveCount() >= executor.getMaximumPoolSize();

        if (saturated) {
            meterRegistry.counter("analysis.job.rejected", "reason", "queue-full").increment();
            throw new BusinessException(
                    ErrorCode.ANALYSIS_QUEUE_FULL,
                    "현재 분석 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."
            );
        }
    }
}
