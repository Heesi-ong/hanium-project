package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * worker 전용 인스턴스에서 QUEUED(대기 중) 분석 작업을 DB에서 폴링해 실행하는 컴포넌트입니다.
 *
 * <p>api/worker를 분리 배포하면 API 인스턴스는 실행 요청을 접수(QUEUED)만 하고 즉시 실행하지
 * 않습니다. 이 폴러가 주기적으로 QUEUED 작업을 가져와 로컬 executor에 투입합니다. 실제 실행
 * 전환은 워커가 {@code claimForExecution()}으로 선점하므로, 여러 worker 인스턴스가 동시에
 * 같은 작업을 집어도 한 워커만 실행합니다. 따라서 분산 락 없이 worker를 여러 개로 늘려
 * 수평 확장할 수 있습니다.</p>
 *
 * <p>{@code analysis.worker.poller.enabled=true} 일 때만 빈으로 등록됩니다. 기본값(monolith)은
 * false라, /run이 커밋 직후 곧바로 로컬 executor로 투입하는 기존 동작을 그대로 사용합니다.</p>
 */
@Component
@ConditionalOnProperty(name = "analysis.worker.poller.enabled", havingValue = "true")
public class QueuedAnalysisJobPoller {

    private static final Logger log = LoggerFactory.getLogger(QueuedAnalysisJobPoller.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisCommandService analysisCommandService;
    private final ThreadPoolTaskExecutor analysisTaskExecutor;
    private final int maxBatch;

    public QueuedAnalysisJobPoller(
            AnalysisJobRepository analysisJobRepository,
            AnalysisCommandService analysisCommandService,
            ThreadPoolTaskExecutor analysisTaskExecutor,
            @Value("${analysis.worker.poller.max-batch:10}") int maxBatch
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisCommandService = analysisCommandService;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.maxBatch = maxBatch;
    }

    @Scheduled(fixedDelayString = "${analysis.worker.poller.interval-ms:1000}")
    public void pollAndDispatch() {
        int freeSlots = freeExecutorSlots();
        if (freeSlots <= 0) {
            // 워커가 이미 가득 찼습니다. 이번 주기에는 새 작업을 가져오지 않고 다음 주기를 기다립니다.
            return;
        }

        int limit = Math.min(freeSlots, maxBatch);
        List<AnalysisJob> queuedJobs = analysisJobRepository.findByStatusOrderByCreatedAtAsc(
                AnalysisStatus.QUEUED,
                PageRequest.of(0, limit)
        );

        if (queuedJobs.isEmpty()) {
            return;
        }

        int dispatched = 0;
        for (AnalysisJob analysisJob : queuedJobs) {
            try {
                analysisCommandService.redispatchQueuedJob(
                        analysisJob.getJobId(),
                        analysisJob.isUseVideoLlm(),
                        analysisJob.isUseOpenAi()
                );
                dispatched++;
            } catch (Exception exception) {
                log.warn("[{}] 대기 작업 투입 중 오류가 발생했습니다.", analysisJob.getJobId(), exception);
            }
        }

        log.info("워커 폴러가 대기 작업을 투입했습니다. freeSlots={}, candidates={}, dispatched={}",
                freeSlots, queuedJobs.size(), dispatched);
    }

    // executor가 지금 추가로 받아들일 수 있는 작업 수(실행 중 여유 + 대기열 여유)를 계산합니다.
    // 이 수만큼만 가져오면 executor 거부(RejectedExecution) 없이 안전하게 투입할 수 있습니다.
    private int freeExecutorSlots() {
        ThreadPoolExecutor executor;
        try {
            executor = analysisTaskExecutor.getThreadPoolExecutor();
        } catch (IllegalStateException notInitialized) {
            return 0;
        }
        if (executor == null) {
            return 0;
        }

        int runningHeadroom = executor.getMaximumPoolSize() - executor.getActiveCount();
        int queueHeadroom = executor.getQueue().remainingCapacity();
        return Math.max(0, runningHeadroom) + Math.max(0, queueHeadroom);
    }
}
