package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.repository.StorageDeletionTaskRepository;
import com.hanium.presentation.domain.storage.type.StorageDeletionTaskStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * StorageDeletionTask(outbox) 행을 폴링해 실제 MinIO 프리픽스 삭제를 수행한다. API
 * 요청 트랜잭션 안에서는 outbox 행만 만들고(원자적 커밋), 실제 오브젝트 삭제는 항상
 * 이 워커가 요청과 무관하게 재시도하며 처리한다(2026-07-23 코드 리뷰 P1-03).
 */
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class StorageDeletionOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(StorageDeletionOutboxWorker.class);

    private final StorageDeletionTaskRepository storageDeletionTaskRepository;
    private final ObjectStorage objectStorage;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final int maxAttempts;
    private final long baseBackoffMinutes;
    private final long maxBackoffMinutes;
    private final Duration lockTtl;
    private final Duration claimLease;
    private final Duration retentionLockTtl;
    private final int completedRetentionDays;
    private final TransactionTemplate transactionTemplate;

    public StorageDeletionOutboxWorker(
            StorageDeletionTaskRepository storageDeletionTaskRepository,
            ObjectStorage objectStorage,
            SchedulerDistributedLock schedulerDistributedLock,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            @Value("${storage.deletion.max-attempts:8}") int maxAttempts,
            @Value("${storage.deletion.base-backoff-minutes:2}") long baseBackoffMinutes,
            @Value("${storage.deletion.max-backoff-minutes:240}") long maxBackoffMinutes,
            @Value("${scheduler.lock.storage-deletion-worker-ttl-minutes:1}") long lockTtlMinutes,
            @Value("${storage.deletion.claim-lease-minutes:5}") long claimLeaseMinutes,
            @Value("${storage.deletion.completed-retention-days:30}") int completedRetentionDays,
            @Value("${scheduler.lock.storage-deletion-retention-ttl-minutes:10}") long retentionLockTtlMinutes
    ) {
        this.storageDeletionTaskRepository = storageDeletionTaskRepository;
        this.objectStorage = objectStorage;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMinutes = baseBackoffMinutes;
        this.maxBackoffMinutes = maxBackoffMinutes;
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
        this.claimLease = Duration.ofMinutes(claimLeaseMinutes);
        this.completedRetentionDays = completedRetentionDays;
        this.retentionLockTtl = Duration.ofMinutes(retentionLockTtlMinutes);
        this.transactionTemplate = new TransactionTemplate(transactionManager);

        // 운영자가 삭제 outbox 적체/재시도 소진 상황을 Prometheus/Grafana에서 바로 볼 수 있게
        // 노출한다(2026-07-23 코드 리뷰 P1-03 "메트릭과 경보" 항목의 최소 구현).
        meterRegistry.gauge(
                "storage.deletion.pending",
                storageDeletionTaskRepository,
                repository -> (double) repository.countByStatus(StorageDeletionTaskStatus.PENDING)
        );
        meterRegistry.gauge(
                "storage.deletion.dead_letter",
                storageDeletionTaskRepository,
                repository -> (double) repository.countByStatus(StorageDeletionTaskStatus.DEAD_LETTER)
        );
    }

    @Scheduled(cron = "${storage.deletion.worker-cron:0 */2 * * * *}")
    public void processPendingDeletions() {
        if (!schedulerDistributedLock.tryLock("storage-deletion-worker", lockTtl)) {
            log.info("스토리지 삭제 outbox 처리를 건너뜁니다. 다른 backend 인스턴스가 락을 보유 중입니다.");
            return;
        }

        List<StorageDeletionTask> dueTasks = storageDeletionTaskRepository
                .findTop50ByStatusAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(
                        StorageDeletionTaskStatus.PENDING,
                        LocalDateTime.now()
                );

        int completed = 0;
        int failed = 0;
        int deadLettered = 0;
        int skipped = 0;

        for (StorageDeletionTask task : dueTasks) {
            ProcessOutcome outcome = processOne(task.getId());

            switch (outcome) {
                case COMPLETED -> completed++;
                case FAILED_WILL_RETRY -> failed++;
                case DEAD_LETTERED -> deadLettered++;
                case SKIPPED -> skipped++;
            }
        }

        if (!dueTasks.isEmpty()) {
            log.info(
                    "스토리지 삭제 outbox 처리 완료. candidates={}, completed={}, failedWillRetry={}, deadLettered={}, skipped={}",
                    dueTasks.size(),
                    completed,
                    failed,
                    deadLettered,
                    skipped
            );
        }
    }

    @Scheduled(cron = "${storage.deletion.completed-cleanup-cron:0 30 3 * * *}")
    public void purgeCompletedTasks() {
        if (!schedulerDistributedLock.tryLock("storage-deletion-retention", retentionLockTtl)) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(completedRetentionDays);
        int deleted = 0;
        int deletedBatch;
        do {
            deletedBatch = transactionTemplate.execute(status -> {
                List<StorageDeletionTask> expiredTasks = storageDeletionTaskRepository
                        .findTop500ByStatusAndCompletedAtBeforeOrderByCompletedAtAsc(
                                StorageDeletionTaskStatus.COMPLETED,
                                cutoff
                        );
                storageDeletionTaskRepository.deleteAllInBatch(expiredTasks);
                return expiredTasks.size();
            });
            deleted += deletedBatch;
        } while (deletedBatch == 500);

        if (deleted > 0) {
            log.info("완료된 스토리지 삭제 outbox 행을 정리했습니다. deleted={}, cutoff={}", deleted, cutoff);
        }
    }

    // DB transaction은 lease 선점과 결과 기록에만 사용한다. MinIO 호출은 transaction 밖에서
    // 실행해 느린 외부 I/O가 DB connection/row lock을 점유하지 않게 한다. 처리 중 프로세스가
    // 죽으면 nextAttemptAt에 저장한 lease가 만료된 뒤 다른 인스턴스가 다시 선점한다.
    private ProcessOutcome processOne(Long taskId) {
        ClaimedTask claimedTask = claim(taskId);
        if (claimedTask == null) {
            return ProcessOutcome.SKIPPED;
        }

        try {
            objectStorage.deleteObjectsWithPrefix(claimedTask.objectKeyPrefix());
            return completeClaim(claimedTask);
        } catch (Exception exception) {
            return failClaim(claimedTask, exception);
        }
    }

    private ClaimedTask claim(Long taskId) {
        return transactionTemplate.execute(status -> {
            StorageDeletionTask task = storageDeletionTaskRepository.findByIdForUpdate(taskId).orElse(null);
            LocalDateTime now = LocalDateTime.now();

            if (task == null
                    || task.getStatus() != StorageDeletionTaskStatus.PENDING
                    || task.getNextAttemptAt().isAfter(now)) {
                return null;
            }

            String token = UUID.randomUUID().toString();
            task.claim(token, now.plus(claimLease));
            return new ClaimedTask(task.getId(), task.getJobId(), task.getObjectKeyPrefix(), token);
        });
    }

    private ProcessOutcome completeClaim(ClaimedTask claimedTask) {
        return transactionTemplate.execute(status -> {
            StorageDeletionTask task = storageDeletionTaskRepository.findByIdForUpdate(claimedTask.id()).orElse(null);
            if (task == null || !task.isClaimedBy(claimedTask.token())) {
                return ProcessOutcome.SKIPPED;
            }
            task.markCompleted();
            return ProcessOutcome.COMPLETED;
        });
    }

    private ProcessOutcome failClaim(ClaimedTask claimedTask, Exception exception) {
        log.warn(
                "STORAGE_DELETION_TASK_ATTEMPT_FAILED id={} jobId={} prefix={} reason={}",
                claimedTask.id(), claimedTask.jobId(), claimedTask.objectKeyPrefix(), exception.toString()
        );

        return transactionTemplate.execute(status -> {
            StorageDeletionTask task = storageDeletionTaskRepository.findByIdForUpdate(claimedTask.id()).orElse(null);
            if (task == null || !task.isClaimedBy(claimedTask.token())) {
                return ProcessOutcome.SKIPPED;
            }

            task.markFailedAndScheduleRetry(exception.getMessage(), maxAttempts, baseBackoffMinutes, maxBackoffMinutes);
            if (task.getStatus() != StorageDeletionTaskStatus.DEAD_LETTER) {
                return ProcessOutcome.FAILED_WILL_RETRY;
            }

            log.error(
                    "STORAGE_DELETION_TASK_DEAD_LETTER id={} jobId={} prefix={} attempts={}",
                    task.getId(), task.getJobId(), task.getObjectKeyPrefix(), task.getAttemptCount()
            );
            return ProcessOutcome.DEAD_LETTERED;
        });
    }

    private record ClaimedTask(Long id, String jobId, String objectKeyPrefix, String token) {
    }

    public enum ProcessOutcome {
        COMPLETED,
        FAILED_WILL_RETRY,
        DEAD_LETTERED,
        SKIPPED
    }
}
