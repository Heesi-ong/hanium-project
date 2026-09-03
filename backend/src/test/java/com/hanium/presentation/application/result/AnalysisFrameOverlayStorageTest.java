package com.hanium.presentation.application.result;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnalysisFrameOverlayStorageTest {

    private static final String JOB_ID = "20260101120000-abcdef12";

    @TempDir
    Path storageRoot;

    private FilePathGenerator filePathGenerator;
    private ObjectStorage objectStorage;
    private AnalysisFrameOverlayStorage frameOverlayStorage;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties(
                storageRoot.toString(),
                storageRoot.resolve("uploads").toString(),
                storageRoot.resolve("results").toString(),
                storageRoot.resolve("temp").toString(),
                storageRoot.resolve("logs").toString(),
                1024L
        );
        filePathGenerator = new FilePathGenerator(storageProperties);
        objectStorage = mock(ObjectStorage.class);
        frameOverlayStorage = new AnalysisFrameOverlayStorage(
                filePathGenerator,
                new LocalFileStorage(),
                objectStorage,
                new ObjectStoragePolicyProperties(false, false)
        );
    }

    private AnalysisEngineResponse responseWithOverlays(List<Map<String, Object>> overlays) {
        return new AnalysisEngineResponse(
                JOB_ID, "success", List.of(), overlays, List.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of()
        );
    }

    @Test
    void persistWritesJpegFilesAndReturnsGalleryMetadata() {
        String base64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        AnalysisEngineResponse response = responseWithOverlays(List.of(
                Map.of("sequence", 1, "timestampSec", 1.0, "poseDetected", true,
                        "gestureDetected", false, "imageBase64", base64),
                Map.of("sequence", 12, "timestampSec", 12.0, "poseDetected", false,
                        "gestureDetected", false, "imageBase64", base64)
        ));

        List<Map<String, Object>> gallery = frameOverlayStorage.persist(JOB_ID, response);

        assertThat(gallery).hasSize(2);
        assertThat(gallery.get(0)).containsEntry("fileName", "frame_001.jpg")
                .containsEntry("poseDetected", true);
        assertThat(gallery.get(1)).containsEntry("fileName", "frame_012.jpg");

        assertThat(Files.exists(filePathGenerator.generateResultFramePath(JOB_ID, "frame_001.jpg"))).isTrue();
        assertThat(Files.exists(filePathGenerator.generateResultFramePath(JOB_ID, "frame_012.jpg"))).isTrue();
        verify(objectStorage).putObject(eq("results/" + JOB_ID + "/frames/frame_001.jpg"), any(), anyLong(), eq("image/jpeg"));
    }

    @Test
    void persistSkipsEntriesWithInvalidBase64ButKeepsOthers() {
        String valid = Base64.getEncoder().encodeToString(new byte[]{9});
        AnalysisEngineResponse response = responseWithOverlays(List.of(
                Map.of("sequence", 1, "imageBase64", "!!not base64!!"),
                Map.of("sequence", 2, "imageBase64", valid)
        ));

        List<Map<String, Object>> gallery = frameOverlayStorage.persist(JOB_ID, response);

        assertThat(gallery).hasSize(1);
        assertThat(gallery.get(0)).containsEntry("fileName", "frame_002.jpg");
    }

    @Test
    void persistReturnsEmptyWhenNoOverlays() {
        assertThat(frameOverlayStorage.persist(JOB_ID, responseWithOverlays(List.of()))).isEmpty();
    }

    @Test
    void readFrameReturnsStoredBytes() {
        byte[] bytes = Base64.getDecoder().decode(Base64.getEncoder().encodeToString(new byte[]{7, 7, 7}));
        frameOverlayStorage.persist(JOB_ID, responseWithOverlays(List.of(
                Map.of("sequence", 3, "imageBase64", Base64.getEncoder().encodeToString(bytes))
        )));

        assertThat(frameOverlayStorage.readFrame(JOB_ID, "frame_003.jpg")).isEqualTo(bytes);
    }

    @Test
    void readFrameRejectsPathTraversalStyleNames() {
        assertThatThrownBy(() -> frameOverlayStorage.readFrame(JOB_ID, "../../secret.json"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void readFrameThrowsFileNotFoundWhenMissingEverywhere() {
        org.mockito.Mockito.when(objectStorage.getObject(any())).thenThrow(new RuntimeException("missing"));

        assertThatThrownBy(() -> frameOverlayStorage.readFrame(JOB_ID, "frame_050.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }
}
