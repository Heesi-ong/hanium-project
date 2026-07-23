package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 재시작 등으로 워커 투입이 유실된 "대기 중(QUEUED)" 작업을 다시 워커 풀에 투입하는 스케줄러입니다.
 *
 * <p>실행 요청이 접수되면 job은 먼저 QUEUED 상태로 저장되고, 백그라운드 워커가 이를 선점해
 * 실행합니다. 그런데 워커 투입은 애플리케이션 메모리 안의 스레드 풀에 의존하므로, 접수 직후
 * 서버가 재시작되면 그 투입 지시가 사라져 job이 QUEUED인 채로 남을 수 있습니다. 이 스케줄러는
 * "접수된 지 오래됐는데 아직 QUEUED인" 작업을 찾아 다시 투입합니다.</p>
 *
 * <p>중복 실행 걱정은 없습니다. 실제 실행 전환은 워커가 {@code claimForExecution()}으로
 * QUEUED일 때만 하므로, 같은 작업이 두 번 투입돼도 한 워커만 실행합니다.</p>
 */
// api 전용 인스턴스(analysis.worker.enabled=false)에서는 재투입 스케줄러를 등록하지 않습니다.
// 재투입은 실행을 담당하는 worker/monolith 인스턴스의 책임입니다.
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class QueuedAnalysisJobResumeService {

    private static final Logger log = LoggerFactory.getLogger(QueuedAnalysisJobResumeService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisCommandService analysisCommandService;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final long staleSeconds;
    private final Duration lockTtl;

    public QueuedAnalysisJobResumeService(
            AnalysisJobRepository analysisJobRepository,
            AnalysisCommandService analysisCommandService,
            SchedulerDistributedLock schedulerDistributedLock,
            @Value("${analysis.queued-job.stale-seconds:60}") long staleSeconds,
            // resume-cron 기본값(매 분)보다 짧아야 합니다. tryLock에는 명시적 unlock이 없어
            // TTL이 다 돼야 풀리므로, 예전 기본값(2분)처럼 TTL이 실행 간격(1분)보다 길면
            // 같은(유일한) 인스턴스가 스스로 잠금을 쥔 채라 다음 틱을 매번 건너뛰게 됩니다
            // (2026-07-23 코드 리뷰 P1-05 — StorageDeletionOutboxWorker에서 실측으로 발견한
            // 것과 동일한 버그 유형). 분 단위로는 실행 간격보다 여유 있게 짧게 두기 어려워
            // 초 단위로 바꿨습니다.
            @Value("${scheduler.lock.queued-job-resume-ttl-seconds:45}") long lockTtlSeconds
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisCommandService = analysisCommandService;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.staleSeconds = staleSeconds;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    @Scheduled(cron = "${analysis.queued-job.resume-cron:15 */1 * * * *}")
    public void resumeStaleQueuedJobs() {
        if (!schedulerDistributedLock.tryLock("queued-job-resume", lockTtl)) {
            log.info("대기 작업 재투입 스케줄러 실행을 건너뜁니다. 다른 backend 인스턴스가 락을 보유 중입니다.");
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusSeconds(staleSeconds);
        List<AnalysisJob> staleQueuedJobs = analysisJobRepository.findByStatusAndStartedAtBefore(
                AnalysisStatus.QUEUED,
                threshold
        );

        int resumedJobs = 0;

        for (AnalysisJob analysisJob : staleQueuedJobs) {
            if (resumeQueuedJob(analysisJob)) {
                resumedJobs++;
            }
        }

        if (!staleQueuedJobs.isEmpty()) {
            log.info(
                    "대기 작업 재투입 스케줄러 실행 완료. threshold={}, candidates={}, resumedJobs={}",
                    threshold,
                    staleQueuedJobs.size(),
                    resumedJobs
            );
        }
    }

    private boolean resumeQueuedJob(AnalysisJob analysisJob) {
        String jobId = analysisJob.getJobId();
        try {
            analysisCommandService.redispatchQueuedJob(
                    jobId,
                    analysisJob.isUseVideoLlm(),
                    analysisJob.isUseOpenAi()
            );
            log.warn("[{}] 재시작 등으로 유실된 대기 작업을 다시 워커에 투입했습니다.", jobId);
            return true;
        } catch (Exception exception) {
            log.warn("[{}] 대기 작업 재투입 중 오류가 발생했습니다.", jobId, exception);
            return false;
        }
    }
}
