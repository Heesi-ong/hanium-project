package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * 실행 중인 분석 파이프라인의 timeout/cancel 체크포인트와 종료 후처리를 담당합니다.
 *
 * <p>마감 초과를 취소 요청보다 먼저 판정하며, 종료가 결정되면 DB 상태, 진행률 캐시,
 * 결과 파일, 메트릭과 실행 시간 타이머를 같은 순서로 반영합니다.</p>
 */
final class AnalysisPipelineTerminationHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineTerminationHandler.class);
    private static final String CANCEL_REASON = "사용자 요청으로 분석 작업이 취소되었습니다.";

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisJobStatusService analysisJobStatusService;
    private final AnalysisProgressService analysisProgressService;
    private final ResultCommandService resultCommandService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    AnalysisPipelineTerminationHandler(
            AnalysisJobRepository analysisJobRepository,
            AnalysisJobStatusService analysisJobStatusService,
            AnalysisProgressService analysisProgressService,
            ResultCommandService resultCommandService,
            MeterRegistry meterRegistry
    ) {
        this(
                analysisJobRepository,
                analysisJobStatusService,
                analysisProgressService,
                resultCommandService,
                meterRegistry,
                Clock.systemUTC()
        );
    }

    AnalysisPipelineTerminationHandler(
            AnalysisJobRepository analysisJobRepository,
            AnalysisJobStatusService analysisJobStatusService,
            AnalysisProgressService analysisProgressService,
            ResultCommandService resultCommandService,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisProgressService = analysisProgressService;
        this.resultCommandService = resultCommandService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    boolean stopIfCancelledOrTimedOut(
            String jobId,
            int lastPercent,
            Timer.Sample sample,
            Instant deadline,
            long timeoutMinutes
    ) {
        if (clock.instant().isAfter(deadline)) {
            terminateTimedOut(jobId, lastPercent, sample, timeoutMinutes);
            return true;
        }

        boolean cancelRequested = analysisJobRepository.findByJobId(jobId)
                .map(AnalysisJob::isCancelRequested)
                .orElse(false);

        if (!cancelRequested) {
            return false;
        }

        terminateCancelled(jobId, lastPercent, sample);
        return true;
    }

    private void terminateTimedOut(
            String jobId,
            int lastPercent,
            Timer.Sample sample,
            long timeoutMinutes
    ) {
        String failReason = "분석이 제한 시간(" + timeoutMinutes + "분)을 초과해 자동으로 종료되었습니다.";
        log.warn("[{}] {}", jobId, failReason);
        if (!analysisJobStatusService.failStatus(jobId, failReason)) {
            log.info("[{}] 이미 확정된 상태가 있어 timeout 후처리를 건너뜁니다.", jobId);
            stopDurationTimer(sample, "superseded");
            return;
        }
        analysisProgressService.fail(jobId, lastPercent, failReason);
        saveFailureResultSafely(jobId, AnalysisStatus.FAILED, failReason);
        meterRegistry.counter("analysis.job.failed", "reason", "timeout").increment();
        stopDurationTimer(sample, "timeout");
    }

    private void terminateCancelled(
            String jobId,
            int lastPercent,
            Timer.Sample sample
    ) {
        log.info("[{}] 취소 요청을 감지해 남은 분석 단계를 중단합니다.", jobId);
        if (!analysisJobStatusService.cancelStatus(jobId)) {
            log.info("[{}] 이미 확정된 상태가 있어 취소 후처리를 건너뜁니다.", jobId);
            stopDurationTimer(sample, "superseded");
            return;
        }
        analysisProgressService.cancel(jobId, lastPercent);
        saveFailureResultSafely(jobId, AnalysisStatus.CANCELLED, CANCEL_REASON);
        meterRegistry.counter("analysis.job.cancelled").increment();
        stopDurationTimer(sample, "cancelled");
    }

    private void saveFailureResultSafely(
            String jobId,
            AnalysisStatus status,
            String reason
    ) {
        try {
            resultCommandService.saveFailureResult(jobId, status.name(), reason);
        } catch (Exception ignored) {
            // 종료 상태 반영과 메트릭 기록이 결과 파일 저장 실패에 가려지지 않게 합니다.
        }
    }

    private void stopDurationTimer(Timer.Sample sample, String outcome) {
        sample.stop(
                Timer.builder("analysis.job.duration")
                        .tag("outcome", outcome)
                        .register(meterRegistry)
        );
    }
}
