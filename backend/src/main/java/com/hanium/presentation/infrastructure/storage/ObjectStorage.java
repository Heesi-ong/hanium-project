package com.hanium.presentation.infrastructure.storage;

import java.io.InputStream;

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
}
