package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 정상 완료와 일반 실패 파이프라인의 공통 종료 후처리를 담당합니다. */
final class AnalysisPipelineOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineOutcomeHandler.class);

    private final AnalysisJobStatusService analysisJobStatusService;
    private final AnalysisProgressService analysisProgressService;
    private final AnalysisResultPersistenceStage resultPersistenceStage;
    private final MeterRegistry meterRegistry;

    AnalysisPipelineOutcomeHandler(
            AnalysisJobStatusService analysisJobStatusService,
            AnalysisProgressService analysisProgressService,
            AnalysisResultPersistenceStage resultPersistenceStage,
            MeterRegistry meterRegistry
    ) {
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisProgressService = analysisProgressService;
        this.resultPersistenceStage = resultPersistenceStage;
        this.meterRegistry = meterRegistry;
    }

    void complete(
            String jobId,
            VideoLlmGenerationMode videoLlmGenerationMode,
            Timer.Sample sample
    ) {
        analysisJobStatusService.completeStatus(jobId, videoLlmGenerationMode);
        meterRegistry.counter("analysis.job.completed").increment();
        stopDurationTimer(sample, "completed");
        analysisProgressService.complete(jobId);
        log.info("STAGE_TRANSITION jobId={} step=COMPLETED percent=100% 분석 파이프라인이 완료되었습니다.", jobId);
    }

    void fail(
            String jobId,
            int lastPercent,
            String failReason,
            String metricReason,
            Timer.Sample sample
    ) {
        failWithoutTimer(jobId, lastPercent, failReason, metricReason);
        stopDurationTimer(sample, "failed");
    }

    void failBeforeExecution(
            String jobId,
            int lastPercent,
            String failReason,
            String metricReason
    ) {
        failWithoutTimer(jobId, lastPercent, failReason, metricReason);
    }

    void stopSkipped(Timer.Sample sample) {
        stopDurationTimer(sample, "skipped");
    }

    private void failWithoutTimer(
            String jobId,
            int lastPercent,
            String failReason,
            String metricReason
    ) {
        analysisJobStatusService.failStatus(jobId, failReason);
        analysisProgressService.fail(jobId, lastPercent, failReason);
        resultPersistenceStage.saveFailureSafely(jobId, failReason);
        meterRegistry.counter("analysis.job.failed", "reason", metricReason).increment();
        log.info(
                "STAGE_TRANSITION jobId={} step=FAILED percent={}% {}",
                jobId,
                lastPercent,
                failReason
        );
    }

    private void stopDurationTimer(Timer.Sample sample, String outcome) {
        sample.stop(
                Timer.builder("analysis.job.duration")
                        .tag("outcome", outcome)
                        .register(meterRegistry)
        );
    }
}
