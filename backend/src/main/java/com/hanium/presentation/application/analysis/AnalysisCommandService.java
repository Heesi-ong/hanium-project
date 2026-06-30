package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisUploadResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Service
public class AnalysisCommandService {

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoFileCommandService videoFileCommandService;
    private final ResultCommandService resultCommandService;
    private final AnalysisEngineClient analysisEngineClient;
    private final VideoLlmEngineClient videoLlmEngineClient;
    private final OpenAiClient openAiClient;
    private final JobIdGenerator jobIdGenerator;

    public AnalysisCommandService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            VideoFileCommandService videoFileCommandService,
            ResultCommandService resultCommandService,
            AnalysisEngineClient analysisEngineClient,
            VideoLlmEngineClient videoLlmEngineClient,
            OpenAiClient openAiClient,
            JobIdGenerator jobIdGenerator
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoFileCommandService = videoFileCommandService;
        this.resultCommandService = resultCommandService;
        this.analysisEngineClient = analysisEngineClient;
        this.videoLlmEngineClient = videoLlmEngineClient;
        this.openAiClient = openAiClient;
        this.jobIdGenerator = jobIdGenerator;
    }

    @Transactional
    public AnalysisUploadResponse uploadVideo(MultipartFile file) {
        String jobId = jobIdGenerator.generate();

        AnalysisJob analysisJob = AnalysisJob.create(jobId);
        AnalysisJob savedJob = analysisJobRepository.save(analysisJob);

        StoredVideoInfo storedVideoInfo = videoFileCommandService.store(
                new VideoUploadCommand(jobId, file)
        );

        UploadedVideo uploadedVideo = UploadedVideo.create(
                jobId,
                storedVideoInfo.originalFileName(),
                storedVideoInfo.storedFilePath(),
                storedVideoInfo.fileType(),
                storedVideoInfo.fileSize()
        );

        uploadedVideoRepository.save(uploadedVideo);

        return AnalysisUploadResponse.of(
                savedJob.getJobId(),
                savedJob.getStatus(),
                storedVideoInfo.originalFileName(),
                storedVideoInfo.storedFilePath(),
                storedVideoInfo.fileSize()
        );
    }

    @Transactional
    public AnalysisStatusResponse runAnalysis(String jobId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        UploadedVideo uploadedVideo = uploadedVideoRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FILE_NOT_FOUND,
                        "업로드된 영상 정보를 찾을 수 없습니다."
                ));

        try {
            analysisJob.startBasicAnalysis();

            AnalysisEngineResponse analysisEngineResponse = analysisEngineClient.analyze(
                    new AnalysisEngineRequest(
                            jobId,
                            uploadedVideo.getStoredFilePath()
                    )
            );

            analysisJob.startVideoLlmAnalysis();

            VideoLlmEngineResponse videoLlmEngineResponse = videoLlmEngineClient.analyze(
                    VideoLlmEngineRequest.defaultOption(
                            jobId,
                            uploadedVideo.getStoredFilePath()
                    )
            );

            analysisJob.startCompacting();

            Map<String, Object> compactAnalysis = resultCommandService.saveEngineResultsAndCompact(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse
            );

            analysisJob.startOpenAiGenerating();

            OpenAiFeedbackResponse openAiFeedbackResponse = openAiClient.generateFeedback(
                    new OpenAiFeedbackRequest(jobId, compactAnalysis)
            );

            resultCommandService.saveOpenAiFeedbackResult(
                    jobId,
                    openAiFeedbackResponse
            );

            analysisJob.startMergingResult();

            resultCommandService.saveFinalResult(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse,
                    openAiFeedbackResponse
            );

            analysisJob.complete();

            return AnalysisStatusResponse.from(analysisJob);
        } catch (BusinessException e) {
            analysisJob.fail(e.getMessage());
            throw e;
        } catch (Exception e) {
            analysisJob.fail(e.getMessage());
            throw new BusinessException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "분석 실행 중 오류가 발생했습니다."
            );
        }
    }
}