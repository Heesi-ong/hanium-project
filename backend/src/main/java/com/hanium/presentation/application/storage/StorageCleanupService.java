package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class StorageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanupService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final FilePathGenerator filePathGenerator;
    private final Duration tempMaxAge;
    private final Duration orphanMaxAge;

    public StorageCleanupService(
            AnalysisJobRepository analysisJobRepository,
            FilePathGenerator filePathGenerator,
            @Value("${storage.cleanup.temp-max-age-hours:6}") long tempMaxAgeHours,
            @Value("${storage.cleanup.orphan-max-age-hours:24}") long orphanMaxAgeHours
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.filePathGenerator = filePathGenerator;
        this.tempMaxAge = Duration.ofHours(tempMaxAgeHours);
        this.orphanMaxAge = Duration.ofHours(orphanMaxAgeHours);
    }

    @Scheduled(cron = "${storage.cleanup.cron:0 0 * * * *}")
    public void cleanupStorage() {
        Instant now = Instant.now();

        int deletedTempDirectories = cleanupOldDirectories(
                filePathGenerator.generateTempRootDirectory(),
                tempMaxAge,
                now
        );
        int deletedUploadDirectories = cleanupOrphanDirectories(
                filePathGenerator.generateUploadRootDirectory(),
                orphanMaxAge,
                now
        );
        int deletedResultDirectories = cleanupOrphanDirectories(
                filePathGenerator.generateResultRootDirectory(),
                orphanMaxAge,
                now
        );

        log.info(
                "스토리지 정리 완료. deletedTempDirectories={}, deletedUploadDirectories={}, deletedResultDirectories={}",
                deletedTempDirectories,
                deletedUploadDirectories,
                deletedResultDirectories
        );
    }

    private int cleanupOldDirectories(Path rootDirectory, Duration maxAge, Instant now) {
        return cleanupDirectories(rootDirectory, directory -> isOlderThan(directory, maxAge, now));
    }

    private int cleanupOrphanDirectories(Path rootDirectory, Duration maxAge, Instant now) {
        return cleanupDirectories(rootDirectory, directory -> {
            String jobId = directory.getFileName().toString();
            return !analysisJobRepository.existsByJobId(jobId) && isOlderThan(directory, maxAge, now);
        });
    }

    private int cleanupDirectories(Path rootDirectory, DirectoryDeletePredicate deletePredicate) {
        if (rootDirectory == null || !Files.isDirectory(rootDirectory)) {
            return 0;
        }

        int deletedDirectories = 0;

        try (Stream<Path> paths = Files.list(rootDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                if (deletePredicate.shouldDelete(path) && deleteDirectory(path)) {
                    deletedDirectories++;
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
