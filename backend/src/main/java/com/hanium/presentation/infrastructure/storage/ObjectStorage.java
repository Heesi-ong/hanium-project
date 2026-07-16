package com.hanium.presentation.infrastructure.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 오브젝트 스토리지(예: MinIO/S3) 접근을 위한 추상화입니다.
 * 기존 {@link LocalFileStorage}/{@link JsonFileStorage}는 이 Unit에서 바뀌지 않으며,
 * 이후 Unit에서 상위 서비스가 이 인터페이스를 사용하도록 단계적으로 전환됩니다.
 */
public interface ObjectStorage {

    void putObject(String objectKey, InputStream inputStream, long size, String contentType);

    InputStream getObject(String objectKey);

    boolean exists(String objectKey);

    void deleteObject(String objectKey);

    void deleteObjectsWithPrefix(String prefix);

    /**
     * Docker 내부 네트워크에서 지정한 오브젝트를 만료 시간 동안 인증 없이 다운로드할 수 있는
     * presigned GET URL을 생성합니다. analysis-engine/video-llm-engine 등 컨테이너 간 통신에 사용합니다.
     */
    String generatePresignedUrl(String objectKey, Duration expiry);

    /**
     * 브라우저/호스트에서 지정한 오브젝트를 만료 시간 동안 인증 없이 다운로드할 수 있는
     * presigned GET URL을 생성합니다. 영상 재생 리다이렉트처럼 사용자가 직접 따라가는 URL에 사용합니다.
     */
    String generatePublicPresignedUrl(String objectKey, Duration expiry);
}
