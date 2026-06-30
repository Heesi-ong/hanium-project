package com.hanium.presentation.infrastructure.storage;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LocalFileStorage {

    public void createDirectory(Path directoryPath) {
        try {
            Files.createDirectories(directoryPath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "디렉토리 생성에 실패했습니다.");
        }
    }

    public void saveFile(MultipartFile file, Path targetPath) {
        try {
            createDirectory(targetPath.getParent());
            file.transferTo(targetPath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "파일 저장에 실패했습니다.");
        }
    }

    public boolean exists(Path path) {
        return Files.exists(path);
    }

    public void validateExists(Path path) {
        if (!exists(path)) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
    }
}