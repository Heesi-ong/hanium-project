package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

// 저장소 정리도 실행/백그라운드 담당 인스턴스(monolith/worker)에서만 돌립니다.
@ConditionalOnProperty(name = "analysis.worker.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class StorageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final FilePathGenerator filePathGenerator;
    private final SchedulerDistributedLock schedulerDistributedLock;
    private final StorageDeletionTaskService storageDeletionTaskService;
    private final Duration tempMaxAge;
    private final Duration orphanMaxAge;
    private final Duration lockTtl;

    public StorageCleanupService(
            AnalysisJobRepository analysisJobRepository,
            FilePathGenerator filePathGenerator,
            SchedulerDistributedLock schedulerDistributedLock,
            StorageDeletionTaskService storageDeletionTaskService,
            @Value("${storage.cleanup.temp-max-age-hours:6}") long tempMaxAgeHours,
            @Value("${storage.cleanup.orphan-max-age-hours:24}") long orphanMaxAgeHours,
            @Value("${scheduler.lock.storage-cleanup-ttl-minutes:10}") long lockTtlMinutes
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.filePathGenerator = filePathGenerator;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.storageDeletionTaskService = storageDeletionTaskService;
        this.tempMaxAge = Duration.ofHours(tempMaxAgeHours);
        this.orphanMaxAge = Duration.ofHours(orphanMaxAgeHours);
        this.lockTtl = Duration.ofMinutes(lockTtlMinutes);
    }

    @Scheduled(cron = "${storage.cleanup.cron:0 0 * * * *}")
    public void cleanupStorage() {
        if (!schedulerDistributedLock.tryLock("storage-cleanup", lockTtl)) {
            log.info("스토리지 정리 스케줄러 실행을 건너뜁니다. 다른 backend 인스턴스가 락을 보유 중입니다.");
            return;
        }

        Instant now = Instant.now();

        int deletedTempDirectories = cleanupOldDirectories(
                filePathGenerator.generateTempRootDirectory(),
                tempMaxAge,
                now
        );
        int deletedUploadDirectories = cleanupOrphanDirectories(
                filePathGenerator.generateUploadRootDirectory(),
                orphanMaxAge,
                now,
                "uploads/"
        );
        int deletedResultDirectories = cleanupOrphanDirectories(
                filePathGenerator.generateResultRootDirectory(),
                orphanMaxAge,
                now,
                "results/"
        );

        log.info(
                "스토리지 정리 완료. deletedTempDirectories={}, deletedUploadDirectories={}, deletedResultDirectories={}",
                deletedTempDirectories,
                deletedUploadDirectories,
                deletedResultDirectories
        );
    }

    private int cleanupOldDirectories(Path rootDirectory, Duration maxAge, Instant now) {
        return cleanupDirectories(
                rootDirectory,
                directory -> isOlderThan(directory, maxAge, now),
                null
        );
    }

    private int cleanupOrphanDirectories(Path rootDirectory, Duration maxAge, Instant now, String objectStoragePrefix) {
        return cleanupDirectories(
                rootDirectory,
                directory -> {
                    String jobId = directory.getFileName().toString();
                    return !analysisJobRepository.existsByJobId(jobId) && isOlderThan(directory, maxAge, now);
                },
                objectStoragePrefix
        );
    }

    private int cleanupDirectories(Path rootDirectory, DirectoryDeletePredicate deletePredicate, String objectStoragePrefix) {
        if (rootDirectory == null || !Files.isDirectory(rootDirectory)) {
            return 0;
        }

        int deletedDirectories = 0;

        try (Stream<Path> paths = Files.list(rootDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                if (!deletePredicate.shouldDelete(path)) {
                    continue;
                }

                try {
                    // 로컬 디렉토리 존재 여부가 곧 "이 orphan을 다음 주기에 다시 발견할 근거"이므로,
                    // MinIO 정리 outbox 행을 먼저 만들어 커밋한 뒤에만 로컬 디렉토리를 지운다.
                    // 순서가 반대라면 로컬 삭제 후 outbox 생성이 실패했을 때 그 prefix는 로컬/DB
                    // 어디에도 흔적이 남지 않아 영영 재시도되지 못한다(2026-07-23 코드 리뷰 P1-03).
                    if (objectStoragePrefix != null) {
                        String jobId = path.getFileName().toString();
                        storageDeletionTaskService.enqueue(
                                jobId,
                                objectStoragePrefix + jobId + "/",
                                StorageDeletionReason.ORPHAN_CLEANUP
                        );
                    }

                    if (deleteDirectory(path)) {
                        deletedDirectories++;
                    }
                } catch (Exception exception) {
                    // 이 디렉토리 하나의 처리 실패가 나머지 orphan/temp 정리까지 막지 않도록
                    // 여기서 잡아 로그만 남긴다. 로컬 디렉토리가 그대로 남아 있으므로 다음
                    // 스케줄 주기에 같은 대상이 다시 발견되어 재시도된다.
                    log.warn(
                            "ORPHAN_CLEANUP_ENQUEUE_FAILED path={} reason={}",
                            path,
                            exception.toString()
                    );
                }
            }
        } catch (IOException e) {
            log.warn("스토리지 정리 대상 디렉토리 목록을 읽지 못했습니다. path={}", rootDirectory, e);
        }

        return deletedDirectories;
    }

    private boolean isOlderThan(Path directory, Duration maxAge, Instant now) {
        try {
            Instant lastModifiedAt = Files.getLastModifiedTime(directory).toInstant();
            return lastModifiedAt.plus(maxAge).isBefore(now);
        } catch (IOException e) {
            log.warn("스토리지 정리 대상 디렉토리 수정 시각을 읽지 못했습니다. path={}", directory, e);
            return false;
        }
    }

    private boolean deleteDirectory(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(this::deletePath);
            return !Files.exists(directory);
        } catch (IOException | RuntimeException e) {
            log.warn("스토리지 정리 중 디렉토리를 삭제하지 못했습니다. path={}", directory, e);
            return false;
        }
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("스토리지 정리 중 파일 삭제에 실패했습니다. path=" + path, e);
        }
    }

    @FunctionalInterface
    private interface DirectoryDeletePredicate {
        boolean shouldDelete(Path directory);
    }
}
