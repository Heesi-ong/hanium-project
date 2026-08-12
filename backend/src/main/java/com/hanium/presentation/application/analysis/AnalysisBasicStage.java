package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 원본 영상 접근 URL을 해석하고 정량 분석 엔진을 호출하는 기본 분석 실행 경계입니다.
 *
 * <p>재분석 작업 ID와 원본 영상 자산 ID는 다를 수 있으므로, 엔진 correlation에는
 * {@code jobId}를 사용하고 다운로드 URL 해석에는 {@code videoAssetJobId}를 사용합니다.</p>
 */
final class AnalysisBasicStage {

    private static final Logger log = LoggerFactory.getLogger(AnalysisBasicStage.class);

    record Result(
            AnalysisEngineResponse response,
            String videoDownloadUrl
    ) {
    }

    private final VideoFileCommandService videoFileCommandService;
    private final AnalysisEngineClient analysisEngineClient;

    AnalysisBasicStage(
            VideoFileCommandService videoFileCommandService,
            AnalysisEngineClient analysisEngineClient
    ) {
        this.videoFileCommandService = videoFileCommandService;
        this.analysisEngineClient = analysisEngineClient;
    }

    Result analyze(
            String jobId,
            String videoAssetJobId,
            String storedFilePath
    ) {
        String videoDownloadUrl = videoFileCommandService.resolveDownloadUrl(
                videoAssetJobId,
                storedFilePath
        );

        AnalysisEngineResponse response = analysisEngineClient.analyze(
                new AnalysisEngineRequest(
                        jobId,
                        storedFilePath,
                        videoDownloadUrl
                )
        );

        log.info("[{}] 기본 분석 응답을 받았습니다.", jobId);
        return new Result(response, videoDownloadUrl);
    }
}
