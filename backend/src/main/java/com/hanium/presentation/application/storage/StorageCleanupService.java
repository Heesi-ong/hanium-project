package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
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
    private final ObjectStorage objectStorage;
    private final Duration tempMaxAge;
    private final Duration orphanMaxAge;
    private final Duration lockTtl;

    public StorageCleanupService(
            AnalysisJobRepository analysisJobRepository,
            FilePathGenerator filePathGenerator,
            SchedulerDistributedLock schedulerDistributedLock,
            ObjectStorage objectStorage,
            @Value("${storage.cleanup.temp-max-age-hours:6}") long tempMaxAgeHours,
            @Value("${storage.cleanup.orphan-max-age-hours:24}") long orphanMaxAgeHours,
            @Value("${scheduler.lock.storage-cleanup-ttl-minutes:10}") long lockTtlMinutes
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.filePathGenerator = filePathGenerator;
        this.schedulerDistributedLock = schedulerDistributedLock;
        this.objectStorage = objectStorage;
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
                if (deletePredicate.shouldDelete(path) && deleteDirectory(path)) {
                    deletedDirectories++;

                    if (objectStoragePrefix != null) {
                        deleteObjectStoragePrefixQuietly(objectStoragePrefix + path.getFileName() + "/");
                    }
                }
            }
        } catch (IOException e) {
            log.warn("스토리지 정리 대상 디렉토리 목록을 읽지 못했습니다. path={}", rootDirectory, e);
        }

        return deletedDirectories;
    }

    /**
     * 로컬 디렉토리 삭제와 별개로 MinIO에 미러링된 오브젝트도 정리합니다. 이 호출이 실패해도
     * 로컬 정리 결과(deletedDirectories 카운트)에는 영향을 주지 않는 best-effort입니다.
     */
    private void deleteObjectStoragePrefixQuietly(String prefix) {
        try {
            objectStorage.deleteObjectsWithPrefix(prefix);
        } catch (Exception exception) {
            log.warn("OBJECT_STORAGE_CLEANUP_FAILED prefix={} reason={}", prefix, exception.toString());
        }
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
