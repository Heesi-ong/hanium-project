package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// 원본 영상 보존 정리도 실행/백그라운드 담당 인스턴스(monolith/worker)에서만 돌립니다.
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class OriginalVideoRetentionService {

    private static final Logger log = LoggerFactory.getLogger(OriginalVideoRetentionService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final FilePathGenerator filePathGenerator;
    private final LocalFileStorage localFileStorage;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final ObjectStorage objectStorage;
    private final long originalVideoRetentionDays;
    private final Duration lockTtl;

    public OriginalVideoRetentionService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            FilePathGenerator filePathGenerator,
            LocalFileStorage localFileStorage,
            SchedulerDistributedLock schedulerDistributedLock,
            ObjectStorage objectStorage,
            @Value("${storage.retention.original-video-days:30}") long originalVideoRetentionDays,
            @Value("${scheduler.lock.original-video-retention-ttl-minutes:10}") long lockTtlMinutes
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.filePathGenerator = filePathGenerator;
        this.localFileStorage = localFileStorage;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.objectStorage = objectStorage;
        this.originalVideoRetentionDays = originalVideoRetentionDays;
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
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
        int failedJobs = 0;

        for (AnalysisJob analysisJob : expiredCompletedJobs) {
            if (deleteOriginalVideo(analysisJob)) {
                deletedJobs++;
            } else {
                failedJobs++;
            }
        }

        log.info(
                "원본 영상 보존 기간 정리 완료. threshold={}, candidates={}, deletedJobs={}, failedJobs={}",
                threshold,
                expiredCompletedJobs.size(),
                deletedJobs,
                failedJobs
        );
    }

    private boolean deleteOriginalVideo(AnalysisJob analysisJob) {
        String jobId = analysisJob.getJobId();
        Path uploadDirectory = filePathGenerator.generateUploadDirectory(jobId);

        try {
            localFileStorage.deleteDirectoryIfExists(uploadDirectory);
            uploadedVideoRepository.findByJobId(jobId)
                    .ifPresent(uploadedVideoRepository::delete);
            deleteObjectStoragePrefixQuietly(jobId);
            log.info("[{}] 보존 기간이 지난 원본 영상 업로드 디렉토리를 삭제했습니다.", jobId);
            return true;
        } catch (Exception exception) {
            log.warn("[{}] 보존 기간이 지난 원본 영상 삭제 중 오류가 발생했습니다.", jobId, exception);
            return false;
        }
    }

    /**
     * 로컬 삭제와 별개로 MinIO에 미러링된 원본 영상 오브젝트도 정리합니다. 이 호출이 실패해도
     * 로컬 삭제가 이미 끝난 뒤이므로 deleteOriginalVideo()의 성공/실패 결과에는 영향을 주지 않습니다
     * (보존 정책은 로컬 디스크 기준으로는 항상 지켜지고, MinIO 정리는 best-effort로 뒤따릅니다).
     */
    private void deleteObjectStoragePrefixQuietly(String jobId) {
        String objectKeyPrefix = "uploads/" + jobId + "/";
        try {
            objectStorage.deleteObjectsWithPrefix(objectKeyPrefix);
        } catch (Exception exception) {
            log.warn(
                    "[{}] OBJECT_STORAGE_RETENTION_CLEANUP_FAILED prefix={} reason={}",
                    jobId,
                    objectKeyPrefix,
                    exception.toString()
            );
        }
    }
}
