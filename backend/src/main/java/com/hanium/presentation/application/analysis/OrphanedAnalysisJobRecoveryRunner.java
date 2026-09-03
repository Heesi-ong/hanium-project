package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 기동 직후, 이전 프로세스가 실행 중(RUNNING)이던 채로 죽어 남은 분석 작업을 즉시 실패 처리합니다.
 *
 * <p>{@code StuckAnalysisJobWatchdogService}는 같은 일을 하지만 {@code started_at} 기준
 * 30분(analysis.stuck-job.max-running-minutes)이 지나야 개입합니다. 그동안 사용자에게는
 * "진행 중"으로 보이고 재시도도 막힙니다. monolith 또는 단일 워커 배포에서는 기동 직후
 * executor가 비어 있으므로 RUNNING 상태로 남은 행은 정의상 전부 orphan입니다 — 30분을
 * 기다릴 필요 없이 여기서 바로 실패로 확정해, 사용자가 즉시 재시도할 수 있게 합니다.</p>
 *
 * <p>워커를 여러 개로 확장하면({@code docker compose up --scale analysis-worker=N}) 형제
 * 워커가 실제로 실행 중인 작업을 orphan으로 오인할 수 있으므로,
 * {@code analysis.startup-recovery.enabled=false}로 꺼야 합니다. 그 경우에도 30분 워치도그가
 * 백스톱으로 남습니다.</p>
 */
@ConditionalOnProperty(
        name = "analysis.startup-recovery.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Component
public class OrphanedAnalysisJobRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanedAnalysisJobRecoveryRunner.class);

    private static final String ORPHANED_JOB_FAIL_REASON =
            "서버 재시작으로 진행 중이던 분석이 중단되었습니다. 다시 시도해주세요.";

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

    public OrphanedAnalysisJobRecoveryRunner(
            AnalysisJobRepository analysisJobRepository,
            AnalysisJobStatusService analysisJobStatusService,
            AnalysisProgressService analysisProgressService,
            ResultCommandService resultCommandService
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisProgressService = analysisProgressService;
        this.resultCommandService = resultCommandService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AnalysisJob> orphanedJobs = analysisJobRepository.findByStatusIn(RUNNING_STATUSES);

        if (orphanedJobs.isEmpty()) {
            return;
        }

        int recoveredJobs = 0;
        for (AnalysisJob orphanedJob : orphanedJobs) {
            if (recoverOrphanedJob(orphanedJob)) {
                recoveredJobs++;
            }
        }

        log.warn(
                "STARTUP_RECOVERY 기동 시 유실된 실행 중 분석 작업을 실패 처리했습니다. candidates={}, recovered={}",
                orphanedJobs.size(),
                recoveredJobs
        );
    }

    private boolean recoverOrphanedJob(AnalysisJob orphanedJob) {
        String jobId = orphanedJob.getJobId();

        try {
            // 워치도그와 동일한 종료 처리: DB 상태 확정 -> 진행률 캐시 -> 결과 JSON.
            // failStatus는 재시도 소진 작업이면 DEAD_LETTER로 전이합니다(워치도그와 동일).
            if (!analysisJobStatusService.failStatus(jobId, ORPHANED_JOB_FAIL_REASON)) {
                log.info("[{}] 상태가 이미 변경되어 startup 복구 후처리를 건너뜁니다.", jobId);
                return false;
            }
            analysisProgressService.fail(jobId, 0, ORPHANED_JOB_FAIL_REASON);
            saveFailureResultSafely(jobId);
            log.warn("[{}] 기동 시 유실된 실행 중 분석 작업을 자동 실패 처리했습니다.", jobId);
            return true;
        } catch (Exception exception) {
            log.warn("[{}] 기동 시 유실된 실행 중 분석 작업 복구 중 오류가 발생했습니다.", jobId, exception);
            return false;
        }
    }

    private void saveFailureResultSafely(String jobId) {
        try {
            resultCommandService.saveFailureResult(
                    jobId,
                    AnalysisStatus.FAILED.name(),
                    ORPHANED_JOB_FAIL_REASON
            );
        } catch (Exception exception) {
            log.warn("[{}] 기동 복구 실패 결과 JSON 저장에 실패했습니다.", jobId, exception);
        }
    }
}
