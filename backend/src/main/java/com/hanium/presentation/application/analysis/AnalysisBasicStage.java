package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
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

        if (response == null || !"success".equalsIgnoreCase(response.status())) {
            String reason = resolveFailureReason(response);
            log.warn("[{}] 기본 분석 엔진이 실패 응답을 반환했습니다. reason={}", jobId, reason);
            throw new BusinessException(ErrorCode.ANALYSIS_ENGINE_ERROR, reason);
        }

        log.info("[{}] 기본 분석 응답을 받았습니다.", jobId);
        return new Result(response, videoDownloadUrl);
    }

    private String resolveFailureReason(AnalysisEngineResponse response) {
        if (response == null) {
            return "기본 분석 엔진이 빈 응답을 반환했습니다.";
        }

        Object reason = JsonMapSupport.copyStringKeyedMap(response.error()).get("reason");
        if (reason instanceof String message && !message.isBlank()) {
            return message;
        }

        return "기본 분석 엔진이 분석 실패 상태를 반환했습니다.";
    }
}
