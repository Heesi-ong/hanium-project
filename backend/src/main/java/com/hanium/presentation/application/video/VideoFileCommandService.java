package com.hanium.presentation.application.video;

import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import com.hanium.presentation.global.properties.VideoProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

@Service
public class VideoFileCommandService {

    private static final Logger log = LoggerFactory.getLogger(VideoFileCommandService.class);

    private final LocalFileStorage localFileStorage;
    private final FilePathGenerator filePathGenerator;
    private final StorageProperties storageProperties;
    private final VideoDurationProbe videoDurationProbe;
    private final VideoProperties videoProperties;
    private final ObjectStorage objectStorage;
    private final ObjectStoragePolicyProperties objectStoragePolicy;

    public VideoFileCommandService(
            LocalFileStorage localFileStorage,
            FilePathGenerator filePathGenerator,
            StorageProperties storageProperties,
            VideoDurationProbe videoDurationProbe,
            VideoProperties videoProperties,
            ObjectStorage objectStorage,
            ObjectStoragePolicyProperties objectStoragePolicy
    ) {
        this.localFileStorage = localFileStorage;
        this.filePathGenerator = filePathGenerator;
        this.storageProperties = storageProperties;
        this.videoDurationProbe = videoDurationProbe;
        this.videoProperties = videoProperties;
        this.objectStorage = objectStorage;
        this.objectStoragePolicy = objectStoragePolicy;
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
        validateVideoDuration(storedPath);
        mirrorToObjectStorage(command.jobId(), extension, storedPath, file);

        return new StoredVideoInfo(
                originalFileName,
                storedPath.toString(),
                fileType,
                file.getSize()
        );
    }

    /** 로컬에 저장한 원본을 MinIO에도 기록합니다. 운영 strict 모드에서는 실패 시 업로드를 거부합니다. */
    private void mirrorToObjectStorage(String jobId, String extension, Path storedPath, MultipartFile file) {
        String objectKey = "uploads/" + jobId + "/original" + extension;
        String contentType = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream");

        try (InputStream inputStream = Files.newInputStream(storedPath)) {
            objectStorage.putObject(objectKey, inputStream, file.getSize(), contentType);
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "OBJECT_STORAGE_MIRROR_FAILED jobId={} objectKey={} reason={}",
                    jobId,
                    objectKey,
                    exception.toString()
            );

            if (objectStoragePolicy.writeRequired()) {
                localFileStorage.deleteFileIfExists(storedPath);

                if (exception instanceof BusinessException businessException) {
                    throw businessException;
                }

                throw new BusinessException(
                        ErrorCode.FILE_UPLOAD_FAILED,
                        "오브젝트 스토리지에 영상을 저장하지 못했습니다."
                );
            }
        }
    }

    /**
     * 업로드된 원본 영상을 오브젝트 스토리지(MinIO)에서 바로 내려받을 수 있는 presigned URL을 생성합니다.
     * MinIO가 응답하지 않거나 아직 미러링되지 않았을 수 있으므로 best-effort이며,
     * 실패 시 null을 반환해 호출부가 기존처럼 로컬 경로(videoPath) 기반으로 계속 동작하게 합니다.
     */
    public String resolveDownloadUrl(String jobId, String storedFilePath) {
        try {
            String objectKey = buildUploadObjectKey(jobId, storedFilePath);

            if (!objectStorage.exists(objectKey)) {
                log.info(
                        "OBJECT_STORAGE_DOWNLOAD_OBJECT_MISSING jobId={} objectKey={} localFallback=true",
                        jobId,
                        objectKey
                );
                return null;
            }

            return objectStorage.generatePresignedUrl(objectKey, Duration.ofHours(1));
        } catch (Exception exception) {
            log.warn(
                    "OBJECT_STORAGE_PRESIGNED_URL_FAILED jobId={} reason={}",
                    jobId,
                    exception.toString()
            );
            return null;
        }
    }

    /**
     * 재분석 접수 전에 원본 영상이 로컬 또는 MinIO 중 적어도 한 곳에 실제 존재하는지 확인합니다.
     * 로컬이 있으면 MinIO 장애와 무관하게 true이며, 로컬이 없을 때 MinIO 상태 확인 실패는
     * "만료됨(false)"으로 숨기지 않고 예외를 전파해 일시 장애를 410으로 오인하지 않게 합니다.
     */
    public boolean sourceExists(String storageJobId, String storedFilePath) {
        Path localPath = Path.of(storedFilePath);
        if (Files.isRegularFile(localPath) && Files.isReadable(localPath)) {
            return true;
        }

        String objectKey = buildUploadObjectKey(storageJobId, storedFilePath);
        return objectStorage.exists(objectKey);
    }

    // 재생 URL은 앱 자체의 영상 접근 토큰(5분, ResultController.VIDEO_ACCESS_TOKEN_EXPIRES_IN_SECONDS)
    // 검증을 통과해야만 발급되지만, 일단 발급되면 그 뒤로는 별도 인증 없이 쓸 수 있는
    // 링크입니다. 짧게 두고 싶지만, 영상을 끊김 없이 끝까지(일시정지 후 이어보기 포함)
    // 재생하려면 최소한 "허용된 최대 영상 길이"만큼은 유효해야 합니다. 그보다 짧으면
    // 긴 영상을 재생하는 도중 URL이 만료돼 재생이 끊깁니다. 그래서 만료 시간을 고정값이
    // 아니라 "최대 영상 길이 + 일시정지/되감기 여유분"으로 계산해, 유출된 링크의 유효
    // 기간을 실제로 필요한 만큼으로만 제한합니다.
    private static final long STREAMING_URL_PLAYBACK_BUFFER_MINUTES = 30L;

    /**
     * 브라우저 영상 재생(스트리밍)을 위한 presigned URL을 생성합니다. resolveDownloadUrl과 달리
     * 오브젝트가 실제로 MinIO에 존재하는지 먼저 확인합니다 - 스트리밍은 여기서 실패를 감지하지
     * 못하면(예: 아직 미러링되지 않은 영상) 브라우저가 깨진 링크로 리다이렉트되어 재생이
     * 실패하기 때문입니다. 존재하지 않거나 확인 자체가 실패하면 null을 반환해 호출부가
     * 기존 로컬 디스크 스트리밍으로 계속 동작하게 합니다.
     */
    public String resolveStreamingUrl(String jobId, String storedFilePath) {
        try {
            String objectKey = buildUploadObjectKey(jobId, storedFilePath);

            if (!objectStorage.exists(objectKey)) {
                return null;
            }

            Duration expiration = Duration.ofMinutes(
                    videoProperties.maxDurationMinutes() + STREAMING_URL_PLAYBACK_BUFFER_MINUTES
            );
            return objectStorage.generatePublicPresignedUrl(objectKey, expiration);
        } catch (Exception exception) {
            log.warn(
                    "OBJECT_STORAGE_STREAMING_URL_FAILED jobId={} reason={}",
                    jobId,
                    exception.toString()
            );
            return null;
        }
    }

    private String buildUploadObjectKey(String jobId, String storedFilePath) {
        String fileName = Path.of(storedFilePath).getFileName().toString();
        String extension = extractExtension(fileName);
        return "uploads/" + jobId + "/original" + extension;
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
        validateAvailableStorageSpace(file);
    }

    private void validateVideoDuration(Path storedPath) {
        Optional<Duration> probedDuration = videoDurationProbe.probe(storedPath);

        if (probedDuration.isEmpty()) {
            if (videoProperties.durationProbeRequired()) {
                localFileStorage.deleteFileIfExists(storedPath);
                throw new BusinessException(
                        ErrorCode.FILE_UPLOAD_FAILED,
                        "영상 재생 시간을 확인할 수 없어 업로드를 중단했습니다."
                );
            }

            return;
        }

        probedDuration.ifPresent(duration -> {
            long maxDurationSeconds = videoProperties.maxDurationMinutes() * 60L;

            if (duration.toSeconds() > maxDurationSeconds) {
                localFileStorage.deleteFileIfExists(storedPath);
                throw new BusinessException(
                        ErrorCode.VIDEO_DURATION_EXCEEDED,
                        "허용된 최대 재생 시간(" + videoProperties.maxDurationMinutes() + "분)을 초과했습니다."
                );
            }
        });
    }

    private void validateAvailableStorageSpace(MultipartFile file) {
        try {
            Path rootPath = Path.of(storageProperties.rootPath());
            long usableBytes = Files.getFileStore(rootPath).getUsableSpace();
            long minFreeBytes = storageProperties.minFreeSpaceMb() * 1024L * 1024L;

            if (usableBytes - file.getSize() < minFreeBytes) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STORAGE_SPACE);
            }
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.FILE_UPLOAD_FAILED,
                    "저장 공간 확인에 실패했습니다."
            );
        }
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
