package com.hanium.presentation.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonFileStorage {

    private final ObjectMapper objectMapper;

    public JsonFileStorage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void saveJson(Path path, Object data) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), data);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "JSON 파일 저장에 실패했습니다.");
        }
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
}