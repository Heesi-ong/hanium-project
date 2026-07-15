package com.hanium.presentation.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonFileStorage {

    private static final Logger log = LoggerFactory.getLogger(JsonFileStorage.class);

    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;

    public JsonFileStorage(ObjectMapper objectMapper, ObjectStorage objectStorage) {
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
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
        try {
            if (!Files.exists(path)) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "JSON 파일을 찾을 수 없습니다.");
            }

            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "JSON 파일 읽기에 실패했습니다.");
        }
    }

    /**
     * 로컬에 저장된 결과 JSON을 오브젝트 스토리지(MinIO)에도 미러링합니다.
     * 아직 어떤 서비스도 이 사본을 실제로 읽지 않으므로(Phase C에서 전환 예정) best-effort입니다 -
     * 로컬 저장은 이미 끝난 뒤라 미러링 실패가 saveJson() 자체를 실패시키지 않습니다.
     */
    private void mirrorToObjectStorage(Path path, Object data) {
        String objectKey = "results/" + path.getParent().getFileName() + "/" + path.getFileName();

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
        }
    }
}
