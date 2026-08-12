package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisBasicStageTest {

    private static final String JOB_ID = "reanalyze-child-job";
    private static final String VIDEO_ASSET_JOB_ID = "original-video-job";
    private static final String STORED_FILE_PATH = "/storage/uploads/original-video-job/video.mp4";

    private VideoFileCommandService videoFileCommandService;
    private AnalysisEngineClient analysisEngineClient;
    private AnalysisBasicStage stage;

    @BeforeEach
    void setUp() {
        videoFileCommandService = mock(VideoFileCommandService.class);
        analysisEngineClient = mock(AnalysisEngineClient.class);
        stage = new AnalysisBasicStage(videoFileCommandService, analysisEngineClient);
    }

    @Test
    void usesVideoAssetJobIdForDownloadAndAnalysisJobIdForEngineCorrelation() {
        String downloadUrl = "http://minio.test/uploads/original-video-job/original.mp4";
        AnalysisEngineResponse response = response();
        when(videoFileCommandService.resolveDownloadUrl(VIDEO_ASSET_JOB_ID, STORED_FILE_PATH))
                .thenReturn(downloadUrl);
        when(analysisEngineClient.analyze(org.mockito.ArgumentMatchers.any(AnalysisEngineRequest.class)))
                .thenReturn(response);

        AnalysisBasicStage.Result result = stage.analyze(
                JOB_ID,
                VIDEO_ASSET_JOB_ID,
                STORED_FILE_PATH
        );

        ArgumentCaptor<AnalysisEngineRequest> requestCaptor = ArgumentCaptor.forClass(AnalysisEngineRequest.class);
        verify(analysisEngineClient).analyze(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(
                new AnalysisEngineRequest(JOB_ID, STORED_FILE_PATH, downloadUrl)
        );
        assertThat(result.response()).isSameAs(response);
        assertThat(result.videoDownloadUrl()).isEqualTo(downloadUrl);
    }

    @Test
    void forwardsNullDownloadUrlForLocalPathFallback() {
        AnalysisEngineResponse response = response();
        when(videoFileCommandService.resolveDownloadUrl(VIDEO_ASSET_JOB_ID, STORED_FILE_PATH))
                .thenReturn(null);
        when(analysisEngineClient.analyze(new AnalysisEngineRequest(JOB_ID, STORED_FILE_PATH, null)))
                .thenReturn(response);

        AnalysisBasicStage.Result result = stage.analyze(
                JOB_ID,
                VIDEO_ASSET_JOB_ID,
                STORED_FILE_PATH
        );

        verify(analysisEngineClient).analyze(new AnalysisEngineRequest(JOB_ID, STORED_FILE_PATH, null));
        assertThat(result.response()).isSameAs(response);
        assertThat(result.videoDownloadUrl()).isNull();
    }

    @Test
    void propagatesEngineBusinessExceptionWithoutChangingItsContract() {
        BusinessException failure = new BusinessException(
                ErrorCode.ANALYSIS_ENGINE_ERROR,
                "기본 분석 엔진 호출에 실패했습니다."
        );
        when(analysisEngineClient.analyze(new AnalysisEngineRequest(JOB_ID, STORED_FILE_PATH, null)))
                .thenThrow(failure);

        assertThatThrownBy(() -> stage.analyze(JOB_ID, VIDEO_ASSET_JOB_ID, STORED_FILE_PATH))
                .isSameAs(failure);
    }

    private AnalysisEngineResponse response() {
        return new AnalysisEngineResponse(
                JOB_ID,
                "completed",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }
}
