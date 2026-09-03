package com.hanium.presentation.application.result;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 분석 엔진이 만든 스켈레톤 오버레이 프레임(base64 JPEG)을 결과 스토리지에 저장하고,
 * 저장된 프레임을 다시 읽어오는 경계입니다.
 *
 * <p>로컬 디스크의 {@code results/{jobId}/frames/} 에 저장하고 MinIO {@code results/{jobId}/frames/}
 * 프리픽스에도 미러링합니다. 결과 삭제 시 기존 {@code results/{jobId}/} 프리픽스 정리에 그대로
 * 포함되므로 별도 삭제 로직이 필요 없습니다. 미러링 실패 처리는 {@code JsonFileStorage}와
 * 동일하게 strict(writeRequired) 모드에서만 전파합니다.</p>
 */
@Service
public class AnalysisFrameOverlayStorage {

    private static final Logger log = LoggerFactory.getLogger(AnalysisFrameOverlayStorage.class);

    // 시퀀스 번호로 생성하는 파일명. 경로 조작이 섞일 수 없는 형식입니다.
    static final Pattern FRAME_FILE_NAME_PATTERN = Pattern.compile("^frame_\\d{3}\\.jpg$");
    private static final String CONTENT_TYPE = "image/jpeg";

    private final FilePathGenerator filePathGenerator;
    private final LocalFileStorage localFileStorage;
    private final ObjectStorage objectStorage;
    private final ObjectStoragePolicyProperties objectStoragePolicy;

    public AnalysisFrameOverlayStorage(
            FilePathGenerator filePathGenerator,
            LocalFileStorage localFileStorage,
            ObjectStorage objectStorage,
            ObjectStoragePolicyProperties objectStoragePolicy
    ) {
        this.filePathGenerator = filePathGenerator;
        this.localFileStorage = localFileStorage;
        this.objectStorage = objectStorage;
        this.objectStoragePolicy = objectStoragePolicy;
    }

    /**
     * 응답에 담긴 오버레이 프레임을 저장하고, 갤러리 메타데이터 목록을 반환합니다.
     * 개별 프레임 디코딩/저장 실패는 해당 프레임만 건너뛰고 계속 진행합니다.
     */
    public List<Map<String, Object>> persist(String jobId, AnalysisEngineResponse response) {
        List<Map<String, Object>> overlays = response == null ? null : response.frameOverlays();
        if (overlays == null || overlays.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> gallery = new ArrayList<>();

        for (Map<String, Object> overlay : overlays) {
            Integer sequence = toInteger(overlay.get("sequence"));
            Object imageBase64 = overlay.get("imageBase64");

            if (sequence == null || !(imageBase64 instanceof String base64) || base64.isBlank()) {
                log.warn("[{}] 오버레이 프레임 항목이 불완전해 건너뜁니다. sequence={}", jobId, sequence);
                continue;
            }

            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException exception) {
                log.warn("[{}] 오버레이 프레임 base64 디코딩 실패로 건너뜁니다. sequence={}", jobId, sequence);
                continue;
            }

            String fileName = "frame_%03d.jpg".formatted(sequence);
            localFileStorage.writeBytes(
                    filePathGenerator.generateResultFramePath(jobId, fileName),
                    bytes
            );
            mirrorToObjectStorage(buildObjectKey(jobId, fileName), bytes);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", sequence);
            item.put("timestampSec", overlay.get("timestampSec"));
            item.put("poseDetected", overlay.getOrDefault("poseDetected", false));
            item.put("gestureDetected", overlay.getOrDefault("gestureDetected", false));
            item.put("fileName", fileName);
            gallery.add(item);
        }

        log.info("[{}] 오버레이 프레임 {}장을 결과 스토리지에 저장했습니다.", jobId, gallery.size());
        return gallery;
    }

    /**
     * 저장된 오버레이 프레임 1장을 바이트로 읽어옵니다. 로컬 디스크를 먼저 보고,
     * 없으면 MinIO에서 시도합니다. 둘 다 없으면 {@link ErrorCode#FILE_NOT_FOUND} 입니다.
     */
    public byte[] readFrame(String jobId, String fileName) {
        if (!FRAME_FILE_NAME_PATTERN.matcher(fileName).matches()) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "요청한 프레임 파일명이 올바르지 않습니다.");
        }

        Path localPath = filePathGenerator.generateResultFramePath(jobId, fileName);
        if (Files.exists(localPath)) {
            try {
                return Files.readAllBytes(localPath);
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "프레임 파일을 읽지 못했습니다.");
            }
        }

        String objectKey = buildObjectKey(jobId, fileName);
        try (InputStream inputStream = objectStorage.getObject(objectKey)) {
            return inputStream.readAllBytes();
        } catch (IOException | RuntimeException exception) {
            log.warn("[{}] 오버레이 프레임을 찾지 못했습니다. objectKey={} reason={}", jobId, objectKey, exception.toString());
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND, "요청한 프레임을 찾을 수 없습니다.");
        }
    }

    private void mirrorToObjectStorage(String objectKey, byte[] bytes) {
        try {
            objectStorage.putObject(
                    objectKey,
                    new ByteArrayInputStream(bytes),
                    bytes.length,
                    CONTENT_TYPE
            );
        } catch (RuntimeException exception) {
            log.warn("OBJECT_STORAGE_MIRROR_FAILED objectKey={} reason={}", objectKey, exception.toString());

            if (objectStoragePolicy.writeRequired()) {
                if (exception instanceof BusinessException businessException) {
                    throw businessException;
                }
                throw new BusinessException(
                        ErrorCode.FILE_UPLOAD_FAILED,
                        "오브젝트 스토리지에 오버레이 프레임을 저장하지 못했습니다. key=" + objectKey
                );
            }
        }
    }

    private String buildObjectKey(String jobId, String fileName) {
        return "results/" + jobId + "/frames/" + fileName;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
