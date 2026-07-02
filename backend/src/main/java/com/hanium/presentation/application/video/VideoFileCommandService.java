package com.hanium.presentation.application.video;

import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

@Service
public class VideoFileCommandService {

    private final LocalFileStorage localFileStorage;
    private final FilePathGenerator filePathGenerator;

    public VideoFileCommandService(
            LocalFileStorage localFileStorage,
            FilePathGenerator filePathGenerator
    ) {
        this.localFileStorage = localFileStorage;
        this.filePathGenerator = filePathGenerator;
    }

    public StoredVideoInfo store(VideoUploadCommand command) {
        MultipartFile file = command.file();

        validateFile(file);

        String originalFileName = file.getOriginalFilename();
        String extension = extractExtension(originalFileName);
        VideoFileType fileType = VideoFileType.fromExtension(extension);

        Path storedPath = filePathGenerator.generateOriginalVideoPath(
                command.jobId(),
                extension
        );

        localFileStorage.saveFile(file, storedPath);

        return new StoredVideoInfo(
                originalFileName,
                storedPath.toString(),
                fileType,
                file.getSize()
        );
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "업로드할 영상 파일이 없습니다."
            );
        }

        String originalFileName = file.getOriginalFilename();

        if (originalFileName == null || originalFileName.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "파일 이름이 올바르지 않습니다."
            );
        }

        String extension = extractExtension(originalFileName);

        if (!VideoFileType.isSupported(extension)) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "지원하지 않는 영상 파일 형식입니다. mp4, mov, avi, mkv 파일만 업로드할 수 있습니다."
            );
        }

        VideoFileType fileType = VideoFileType.fromExtension(extension);
        validateFileSignature(file, fileType);
    }

    private void validateFileSignature(MultipartFile file, VideoFileType fileType) {
        byte[] header = readHeader(file);

        if (!VideoSignatureValidator.matches(fileType, header)) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_FILE_CONTENT,
                    "파일 내용이 확장자와 일치하지 않습니다."
            );
        }
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(VideoSignatureValidator.HEADER_BYTES_TO_READ);
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.FILE_UPLOAD_FAILED,
                    "파일 내용을 확인할 수 없습니다."
            );
        }
    }

    private String extractExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf(".");

        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "파일 확장자가 없습니다."
            );
        }

        return fileName.substring(lastDotIndex).toLowerCase();
    }
}
