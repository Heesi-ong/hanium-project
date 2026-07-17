package com.hanium.presentation.application.storage;

import com.hanium.presentation.global.properties.MinioProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.MinioObjectStorage;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION_TEST", matches = "true")
class ObjectStorageBackfillMinioIntegrationTest {

    @TempDir
    private Path tempDir;

    @Test
    void backfillUploadsLocalFilesAndSkipsThemOnRerun() throws Exception {
        String endpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://localhost:9000");
        String publicEndpoint = System.getenv().getOrDefault("MINIO_PUBLIC_ENDPOINT", endpoint);
        String accessKey = System.getenv("MINIO_ACCESS_KEY");
        String secretKey = System.getenv("MINIO_SECRET_KEY");
        String bucketName = System.getenv().getOrDefault("MINIO_BUCKET_NAME", "hanium-storage");
        MinioClient minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        MinioObjectStorage objectStorage = new MinioObjectStorage(
                minioClient,
                new MinioProperties(endpoint, publicEndpoint, accessKey, secretKey, bucketName)
        );
        ObjectStorageBackfillRunner runner = new ObjectStorageBackfillRunner(
                mock(FilePathGenerator.class),
                objectStorage,
                mock(ConfigurableApplicationContext.class)
        );
        String jobId = "backfill-it-" + System.currentTimeMillis();
        String prefix = "uploads/" + jobId + "/";
        Path uploadRoot = tempDir.resolve("uploads");
        Path jobDirectory = uploadRoot.resolve(jobId);
        Files.createDirectories(jobDirectory);
        Files.writeString(jobDirectory.resolve("original.mp4"), "video-content");
        Files.writeString(jobDirectory.resolve("metadata.json"), "{\"ready\":true}");

        try {
            ObjectStorageBackfillRunner.BackfillResult first =
                    runner.backfillRootDirectory(uploadRoot, "uploads/");
            ObjectStorageBackfillRunner.BackfillResult second =
                    runner.backfillRootDirectory(uploadRoot, "uploads/");

            assertThat(first.scanned).isEqualTo(2);
            assertThat(first.uploaded).isEqualTo(2);
            assertThat(first.skipped).isZero();
            assertThat(first.failed).isZero();
            assertThat(second.scanned).isEqualTo(2);
            assertThat(second.uploaded).isZero();
            assertThat(second.skipped).isEqualTo(2);
            assertThat(second.failed).isZero();

            try (InputStream downloaded = objectStorage.getObject(prefix + "metadata.json")) {
                assertThat(new String(downloaded.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("{\"ready\":true}");
            }
        } finally {
            objectStorage.deleteObjectsWithPrefix(prefix);
        }
    }
}
