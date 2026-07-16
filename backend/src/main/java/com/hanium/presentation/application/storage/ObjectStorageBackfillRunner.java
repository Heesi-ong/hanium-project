package com.hanium.presentation.application.storage;

import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * MinIO 이중 쓰기(Phase B1/B2)가 도입되기 전에 로컬 디스크에만 저장되어 있던 기존
 * uploads/results 파일을 MinIO로 일회성 백필하는 배치 러너입니다.
 *
 * 평소 애플리케이션 기동 시에는 절대 동작하지 않으며, storage.backfill.enabled=true를
 * 명시적으로 켰을 때만 실행되고 끝나면 애플리케이션을 종료합니다.
 *
 * 실행 예시(운영 1회성 실행):
 *   docker compose run --rm -e STORAGE_BACKFILL_ENABLED=true backend
 * 로컬 실행:
 *   ./gradlew bootRun --args='--storage.backfill.enabled=true'
 *
 * 이미 MinIO에 존재하는 오브젝트는 건너뛰므로(idempotent) 중간에 실패해도 다시 실행하면
 * 이어서 진행됩니다. 개별 파일 업로드 실패는 warn 로그만 남기고 나머지 파일 백필을 계속합니다.
 */
@ConditionalOnProperty(name = "storage.backfill.enabled", havingValue = "true")
@Component
public class ObjectStorageBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageBackfillRunner.class);

    private final FilePathGenerator filePathGenerator;
    private final ObjectStorage objectStorage;
    private final ConfigurableApplicationContext applicationContext;

    public ObjectStorageBackfillRunner(
            FilePathGenerator filePathGenerator,
            ObjectStorage objectStorage,
            ConfigurableApplicationContext applicationContext
    ) {
        this.filePathGenerator = filePathGenerator;
        this.objectStorage = objectStorage;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("OBJECT_STORAGE_BACKFILL_START");

        BackfillResult uploadResult = backfillRootDirectory(filePathGenerator.generateUploadRootDirectory(), "uploads/");
        BackfillResult resultResult = backfillRootDirectory(filePathGenerator.generateResultRootDirectory(), "results/");

        log.info(
                "OBJECT_STORAGE_BACKFILL_DONE uploads[scanned={}, uploaded={}, skipped={}, failed={}] "
                        + "results[scanned={}, uploaded={}, skipped={}, failed={}]",
                uploadResult.scanned, uploadResult.uploaded, uploadResult.skipped, uploadResult.failed,
                resultResult.scanned, resultResult.uploaded, resultResult.skipped, resultResult.failed
        );

        System.exit(SpringApplication.exit(applicationContext, () -> 0));
    }

    BackfillResult backfillRootDirectory(Path rootDirectory, String objectKeyPrefix) {
        BackfillResult result = new BackfillResult();

        if (rootDirectory == null || !Files.isDirectory(rootDirectory)) {
            return result;
        }

        try (Stream<Path> jobDirectories = Files.list(rootDirectory)) {
            for (Path jobDirectory : jobDirectories.filter(Files::isDirectory).toList()) {
                backfillJobDirectory(jobDirectory, objectKeyPrefix, result);
            }
        } catch (IOException e) {
            log.warn("OBJECT_STORAGE_BACKFILL_LIST_FAILED rootDirectory={}", rootDirectory, e);
        }

        return result;
    }

    private void backfillJobDirectory(Path jobDirectory, String objectKeyPrefix, BackfillResult result) {
        String jobId = jobDirectory.getFileName().toString();

        try (Stream<Path> files = Files.list(jobDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                result.scanned++;
                backfillFile(file, objectKeyPrefix + jobId + "/" + file.getFileName(), result);
            }
        } catch (IOException e) {
            log.warn("OBJECT_STORAGE_BACKFILL_LIST_FAILED jobDirectory={}", jobDirectory, e);
        }
    }

    private void backfillFile(Path file, String objectKey, BackfillResult result) {
        try {
            if (objectStorage.exists(objectKey)) {
                result.skipped++;
                return;
            }

            try (InputStream inputStream = Files.newInputStream(file)) {
                objectStorage.putObject(objectKey, inputStream, Files.size(file), resolveContentType(file));
            }

            result.uploaded++;
        } catch (Exception exception) {
            result.failed++;
            log.warn("OBJECT_STORAGE_BACKFILL_FILE_FAILED objectKey={} reason={}", objectKey, exception.toString());
        }
    }

    private String resolveContentType(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".json")) {
            return "application/json";
        } else if (fileName.endsWith(".mp4")) {
            return "video/mp4";
        } else if (fileName.endsWith(".mov")) {
            return "video/quicktime";
        } else if (fileName.endsWith(".avi")) {
            return "video/x-msvideo";
        } else if (fileName.endsWith(".mkv")) {
            return "video/x-matroska";
        }

        return "application/octet-stream";
    }

    static final class BackfillResult {
        int scanned;
        int uploaded;
        int skipped;
        int failed;
    }
}
