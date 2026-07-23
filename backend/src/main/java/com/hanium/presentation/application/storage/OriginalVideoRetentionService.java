package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

// 원본 영상 보존 정리도 실행/백그라운드 담당 인스턴스(monolith/worker)에서만 돌립니다.
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class OriginalVideoRetentionService {

    private static final Logger log = LoggerFactory.getLogger(OriginalVideoRetentionService.class);

    private enum RetentionOutcome {
        DELETED,
        SKIPPED,
        FAILED
    }

    private record RetentionPlan(boolean delete, String videoStorageJobId) {

        private static RetentionPlan skipped() {
            return new RetentionPlan(false, null);
        }

        private static RetentionPlan delete(String videoStorageJobId) {
            return new RetentionPlan(true, videoStorageJobId);
        }
    }

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final FilePathGenerator filePathGenerator;
    private final LocalFileStorage localFileStorage;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final StorageDeletionTaskService storageDeletionTaskService;
    private final long originalVideoRetentionDays;
    private final Duration lockTtl;
    private final TransactionTemplate transactionTemplate;

    public OriginalVideoRetentionService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            FilePathGenerator filePathGenerator,
            LocalFileStorage localFileStorage,
            SchedulerDistributedLock schedulerDistributedLock,
            StorageDeletionTaskService storageDeletionTaskService,
            PlatformTransactionManager transactionManager,
            @Value("${storage.retention.original-video-days:30}") long originalVideoRetentionDays,
            @Value("${scheduler.lock.original-video-retention-ttl-minutes:10}") long lockTtlMinutes
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.filePathGenerator = filePathGenerator;
        this.localFileStorage = localFileStorage;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.storageDeletionTaskService = storageDeletionTaskService;
        this.originalVideoRetentionDays = originalVideoRetentionDays;
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${storage.retention.cron:0 0 3 * * *}")
    public void cleanupExpiredOriginalVideos() {
        if (!schedulerDistributedLock.tryLock("original-video-retention", lockTtl)) {
            log.info("원본 영상 보존 기간 정리 실행을 건너뜁니다. 다른 backend 인스턴스가 락을 보유 중입니다.");
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusDays(originalVideoRetentionDays);
        List<AnalysisJob> expiredCompletedJobs = analysisJobRepository.findByStatusAndCompletedAtBefore(
                AnalysisStatus.COMPLETED,
                threshold
        );

        int deletedJobs = 0;
        int skippedJobs = 0;
        int failedJobs = 0;
        Set<Long> processedAssetIds = new HashSet<>();

        for (AnalysisJob analysisJob : expiredCompletedJobs) {
            RetentionOutcome outcome = deleteOriginalVideo(
                    analysisJob,
                    threshold,
                    processedAssetIds
            );
            switch (outcome) {
                case DELETED -> deletedJobs++;
                case SKIPPED -> skippedJobs++;
                case FAILED -> failedJobs++;
            }
        }

        log.info(
                "원본 영상 보존 기간 정리 완료. threshold={}, candidates={}, deletedJobs={}, skippedJobs={}, failedJobs={}",
                threshold,
                expiredCompletedJobs.size(),
                deletedJobs,
                skippedJobs,
                failedJobs
        );
    }

    private RetentionOutcome deleteOriginalVideo(
            AnalysisJob analysisJob,
            LocalDateTime threshold,
            Set<Long> processedAssetIds
    ) {
        String jobId = analysisJob.getJobId();
        Long videoAssetId = analysisJob.getVideoAssetId();

        if (videoAssetId != null && !processedAssetIds.add(videoAssetId)) {
            return RetentionOutcome.SKIPPED;
        }

        RetentionPlan plan;
        try {
            // 참조 job 행 잠금 → asset 행 잠금 → 참조 재확인 → DB 삭제/outbox 생성을 같은
            // transaction에 둡니다. 재분석 접수도 source job 뒤 asset을 잠그므로 어느 쪽이
            // 먼저 잠금을 얻든 삭제와 child 생성이 직렬화됩니다. local 파일은 commit 성공
            // 뒤에만 삭제합니다.
            plan = transactionTemplate.execute(status -> {
                // MySQL FK ON DELETE SET NULL은 asset 삭제 시 참조 job 행도 갱신합니다.
                // API/결과 삭제와 같은 순서(job -> asset)로 잠그기 위해 참조 job을 ID 순으로
                // 먼저 모두 잠급니다. asset -> job 역순이면 재분석 접수와 교착될 수 있습니다.
                List<AnalysisJob> lockedReferences = videoAssetId == null
                        ? analysisJobRepository.findByJobIdForUpdate(jobId).stream().toList()
                        : analysisJobRepository.findAllByVideoAssetIdForUpdate(videoAssetId);
                UploadedVideo videoAsset = lockVideoAsset(videoAssetId, jobId);
                if (videoAsset == null) {
                    return RetentionPlan.skipped();
                }

                boolean protectedReferenceExists = lockedReferences.stream()
                        .anyMatch(reference -> reference.getStatus() != AnalysisStatus.COMPLETED
                                || reference.getCompletedAt() == null
                                || reference.getCompletedAt().isAfter(threshold));
                if (videoAssetId != null && protectedReferenceExists) {
                    log.info(
                            "[{}] 같은 원본 asset을 사용하는 최신 또는 미완료 작업이 있어 retention 삭제를 보류합니다. videoAssetId={}",
                            jobId,
                            videoAssetId
                    );
                    return RetentionPlan.skipped();
                }

                String videoStorageJobId = videoAsset.getJobId();
                uploadedVideoRepository.delete(videoAsset);
                storageDeletionTaskService.enqueue(
                        videoStorageJobId,
                        "uploads/" + videoStorageJobId + "/",
                        StorageDeletionReason.ORIGINAL_VIDEO_RETENTION
                );
                return RetentionPlan.delete(videoStorageJobId);
            });
        } catch (Exception exception) {
            log.warn("[{}] 보존 기간이 지난 원본 영상 삭제 중 오류가 발생했습니다.", jobId, exception);
            return RetentionOutcome.FAILED;
        }

        if (plan == null || !plan.delete()) {
            return RetentionOutcome.SKIPPED;
        }

        Path uploadDirectory = filePathGenerator.generateUploadDirectory(plan.videoStorageJobId());
        try {
            localFileStorage.deleteDirectoryIfExists(uploadDirectory);
        } catch (Exception exception) {
            // DB/outbox는 이미 커밋됐으므로 원본 asset을 되살릴 수 없습니다. 로컬 고아 파일은
            // StorageCleanupService가 후속 정리하며, MinIO 삭제는 outbox가 재시도합니다.
            log.warn(
                    "[{}] retention DB/outbox 커밋 후 로컬 원본 삭제에 실패했습니다. videoStorageJobId={}",
                    jobId,
                    plan.videoStorageJobId(),
                    exception
            );
        }

        log.info(
                "[{}] 보존 기간이 지난 원본 영상 업로드 디렉토리 정리를 접수했습니다. videoStorageJobId={}",
                jobId,
                plan.videoStorageJobId()
        );
        return RetentionOutcome.DELETED;
    }

    private UploadedVideo lockVideoAsset(Long videoAssetId, String legacyJobId) {
        if (videoAssetId != null) {
            return uploadedVideoRepository.findByIdForUpdate(videoAssetId)
                    .or(() -> uploadedVideoRepository.findByJobIdForUpdate(legacyJobId))
                    .orElse(null);
        }
        return uploadedVideoRepository.findByJobIdForUpdate(legacyJobId).orElse(null);
    }
}
