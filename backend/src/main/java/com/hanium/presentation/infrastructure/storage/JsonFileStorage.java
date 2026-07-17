package com.hanium.presentation.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonFileStorage {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStorage.class);

    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;
    private final ObjectStoragePolicyProperties objectStoragePolicy;

    public JsonFileStorage(
            ObjectMapper objectMapper,
            ObjectStorage objectStorage,
            ObjectStoragePolicyProperties objectStoragePolicy
    ) {
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.objectStoragePolicy = objectStoragePolicy;
    }

    public void saveJson(Path path, Object data) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), data);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "JSON 파일 저장에 실패했습니다.");
        }

        mirrorToObjectStorage(path, data);
    }

    public <T> T readJson(Path path, Class<T> type) {
        if (!objectStoragePolicy.readPreferred() && Files.exists(path)) {
            return readLocalJson(path, type);
        }

        T objectStorageValue = readObjectStorageJson(path, type);
        if (objectStorageValue != null) {
            return objectStorageValue;
        }

        return readLocalJson(path, type);
    }

    private <T> T readLocalJson(Path path, Class<T> type) {
        try {
            if (!Files.exists(path)) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "JSON 파일을 찾을 수 없습니다.");
            }

            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "JSON 파일 읽기에 실패했습니다.");
        }
    }

    private <T> T readObjectStorageJson(Path path, Class<T> type) {
        String objectKey = buildObjectKey(path);

        try (InputStream inputStream = objectStorage.getObject(objectKey)) {
            return objectMapper.readValue(inputStream, type);
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "OBJECT_STORAGE_READ_FAILED objectKey={} localFallback={} reason={}",
                    objectKey,
                    Files.exists(path),
                    exception.toString()
            );
            return null;
        }
    }

    /** 로컬에 저장한 결과 JSON을 MinIO에도 기록합니다. 운영 strict 모드에서는 실패를 전파합니다. */
    private void mirrorToObjectStorage(Path path, Object data) {
        String objectKey = buildObjectKey(path);

        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(data);
            objectStorage.putObject(
                    objectKey,
                    new ByteArrayInputStream(jsonBytes),
                    jsonBytes.length,
                    "application/json"
            );
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "OBJECT_STORAGE_MIRROR_FAILED objectKey={} reason={}",
                    objectKey,
                    exception.toString()
            );

            if (objectStoragePolicy.writeRequired()) {
                deleteLocalMirrorAfterStrictWriteFailure(path, objectKey);

                if (exception instanceof BusinessException businessException) {
                    throw businessException;
                }

                throw new BusinessException(
                        ErrorCode.FILE_UPLOAD_FAILED,
                        "오브젝트 스토리지에 JSON 결과를 저장하지 못했습니다. key=" + objectKey
                );
            }
        }
    }

    private String buildObjectKey(Path path) {
        return "results/" + path.getParent().getFileName() + "/" + path.getFileName();
    }

    private void deleteLocalMirrorAfterStrictWriteFailure(Path path, String objectKey) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupException) {
            log.error(
                    "OBJECT_STORAGE_STRICT_WRITE_LOCAL_CLEANUP_FAILED objectKey={} path={}",
                    objectKey,
                    path,
                    cleanupException
            );
        }
    }
}
