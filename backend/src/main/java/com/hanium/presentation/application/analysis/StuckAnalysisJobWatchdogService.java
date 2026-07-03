package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StuckAnalysisJobWatchdogService {

    private static final Logger log = LoggerFactory.getLogger(StuckAnalysisJobWatchdogService.class);

    private static final String STUCK_JOB_FAIL_REASON =
            "서버 재시작 또는 응답 없음으로 분석이 중단되어 자동으로 실패 처리되었습니다.";

    private static final List<AnalysisStatus> RUNNING_STATUSES = List.of(
            AnalysisStatus.BASIC_ANALYZING,
            AnalysisStatus.VIDEO_LLM_ANALYZING,
            AnalysisStatus.COMPACTING,
            AnalysisStatus.OPENAI_GENERATING,
            AnalysisStatus.MERGING_RESULT
    );

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisJobStatusService analysisJobStatusService;
    private final AnalysisProgressService analysisProgressService;
    private final ResultCommandService resultCommandService;
    private final long maxRunningMinutes;

    public StuckAnalysisJobWatchdogService(
            AnalysisJobRepository analysisJobRepository,
            AnalysisJobStatusService analysisJobStatusService,
            AnalysisProgressService analysisProgressService,
            ResultCommandService resultCommandService,
            @Value("${analysis.stuck-job.max-running-minutes:30}") long maxRunningMinutes
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisProgressService = analysisProgressService;
        this.resultCommandService = resultCommandService;
        this.maxRunningMinutes = maxRunningMinutes;
    }

    @Scheduled(cron = "${analysis.stuck-job.cron:0 */10 * * * *}")
    public void failStuckAnalysisJobs() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(maxRunningMinutes);
        List<AnalysisJob> stuckJobs = analysisJobRepository.findByStatusInAndStartedAtBefore(
                RUNNING_STATUSES,
                threshold
        );

        int failedJobs = 0;

        for (AnalysisJob analysisJob : stuckJobs) {
            if (failStuckJob(analysisJob)) {
                failedJobs++;
            }
        }

        log.info(
                "멈춘 분석 작업 워치도그 실행 완료. threshold={}, candidates={}, failedJobs={}",
                threshold,
                stuckJobs.size(),
                failedJobs
        );
    }

    private boolean failStuckJob(AnalysisJob analysisJob) {
        String jobId = analysisJob.getJobId();

        try {
            analysisJobStatusService.failStatus(jobId, STUCK_JOB_FAIL_REASON);
            analysisProgressService.fail(jobId, 0, STUCK_JOB_FAIL_REASON);
            saveFailureResultSafely(jobId);
            log.warn("[{}] 멈춘 분석 작업을 자동 실패 처리했습니다.", jobId);
            return true;
        } catch (Exception exception) {
            log.warn("[{}] 멈춘 분석 작업 자동 실패 처리 중 오류가 발생했습니다.", jobId, exception);
            return false;
        }
    }

    private void saveFailureResultSafely(String jobId) {
        try {
            resultCommandService.saveFailureResult(
                    jobId,
                    AnalysisStatus.FAILED.name(),
                    STUCK_JOB_FAIL_REASON
            );
        } catch (Exception exception) {
            log.warn("[{}] 멈춘 분석 작업 실패 결과 JSON 저장에 실패했습니다.", jobId, exception);
        }
    }
}
