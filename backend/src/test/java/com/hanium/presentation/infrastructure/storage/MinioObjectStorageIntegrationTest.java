package com.hanium.presentation.infrastructure.storage;

import com.hanium.presentation.global.properties.MinioProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MinIO 서버(docker-compose의 minio 서비스)에 연결해 MinioObjectStorage를 검증합니다.
 * MinIO가 떠 있지 않은 일반 CI/./gradlew test 실행에서는 기본적으로 건너뜁니다.
 *
 * 수동 실행 방법:
 *   docker compose up -d minio minio-init
 *   MINIO_INTEGRATION_TEST=true MINIO_ACCESS_KEY=<.env의 MINIO_ROOT_USER> \
 *     MINIO_SECRET_KEY=<.env의 MINIO_ROOT_PASSWORD> \
 *     ./gradlew test --tests "*MinioObjectStorageIntegrationTest*"
 */
@EnabledIfEnvironmentVariable(named = "MINIO_INTEGRATION_TEST", matches = "true")
class MinioObjectStorageIntegrationTest {

    private final String endpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://localhost:9000");
    private final String publicEndpoint = System.getenv().getOrDefault("MINIO_PUBLIC_ENDPOINT", "http://localhost:9000");
    private final String accessKey = System.getenv("MINIO_ACCESS_KEY");
    private final String secretKey = System.getenv("MINIO_SECRET_KEY");
    private final String bucketName = System.getenv().getOrDefault("MINIO_BUCKET_NAME", "hanium-storage");

    private MinioObjectStorage createObjectStorage() {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        MinioProperties minioProperties = new MinioProperties(endpoint, publicEndpoint, accessKey, secretKey, bucketName);
        return new MinioObjectStorage(minioClient, minioProperties);
    }

    @Test
    void putGetExistsDeleteRoundTrip() throws Exception {
        MinioObjectStorage objectStorage = createObjectStorage();
        String objectKey = "test/minio-object-storage-integration-" + System.currentTimeMillis() + ".txt";
        String content = "hanium minio integration test";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);

        assertThat(objectStorage.exists(objectKey)).isFalse();

        objectStorage.putObject(objectKey, new ByteArrayInputStream(contentBytes), contentBytes.length, "text/plain");

        assertThat(objectStorage.exists(objectKey)).isTrue();

        try (InputStream downloaded = objectStorage.getObject(objectKey)) {
            String downloadedContent = new String(downloaded.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(downloadedContent).isEqualTo(content);
        }

        objectStorage.deleteObject(objectKey);

        assertThat(objectStorage.exists(objectKey)).isFalse();
    }

    @Test
    void deleteObjectsWithPrefixRemovesAllMatchingObjects() throws Exception {
        MinioObjectStorage objectStorage = createObjectStorage();
        String prefix = "test/prefix-delete-" + System.currentTimeMillis() + "/";
        String keyA = prefix + "a.txt";
        String keyB = prefix + "b.txt";

        objectStorage.putObject(keyA, new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");
        objectStorage.putObject(keyB, new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");

        assertThat(objectStorage.exists(keyA)).isTrue();
        assertThat(objectStorage.exists(keyB)).isTrue();

        objectStorage.deleteObjectsWithPrefix(prefix);

        assertThat(objectStorage.exists(keyA)).isFalse();
        assertThat(objectStorage.exists(keyB)).isFalse();
    }

    @Test
    void generatePresignedUrlReturnsDownloadableUrl() throws Exception {
        MinioObjectStorage objectStorage = createObjectStorage();
        String objectKey = "test/presigned-url-" + System.currentTimeMillis() + ".txt";
        byte[] contentBytes = "presigned url test".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(objectKey, new ByteArrayInputStream(contentBytes), contentBytes.length, "text/plain");

        String url = objectStorage.generatePresignedUrl(objectKey, java.time.Duration.ofMinutes(5));

        assertThat(url).isNotBlank();
        assertThat(url).contains(objectKey);

        objectStorage.deleteObject(objectKey);
    }

    @Test
    void generatePublicPresignedUrlUsesPublicEndpointHost() throws Exception {
        MinioObjectStorage objectStorage = createObjectStorage();
        String objectKey = "test/public-presigned-url-" + System.currentTimeMillis() + ".txt";
        byte[] contentBytes = "public presigned url test".getBytes(StandardCharsets.UTF_8);
        objectStorage.putObject(objectKey, new ByteArrayInputStream(contentBytes), contentBytes.length, "text/plain");

        String url = objectStorage.generatePublicPresignedUrl(objectKey, java.time.Duration.ofMinutes(5));

        assertThat(url).startsWith(publicEndpoint);
        assertThat(url).contains(objectKey);

        objectStorage.deleteObject(objectKey);
    }
}
