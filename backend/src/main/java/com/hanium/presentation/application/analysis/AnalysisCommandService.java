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

import java.util.List;
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

    @Transactional(noRollbackFor = BusinessException.class)
    public AnalysisStatusResponse runAnalysis(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateRunnable(analysisJob);

        return executeAnalysis(
                analysisJob,
                useVideoLlm,
                useOpenAi
        );
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AnalysisStatusResponse retryAnalysis(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateRetryable(analysisJob);

        analysisJob.resetForRetry();

        return executeAnalysis(
                analysisJob,
                useVideoLlm,
                useOpenAi
        );
    }

    private AnalysisStatusResponse executeAnalysis(
            AnalysisJob analysisJob,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        String jobId = analysisJob.getJobId();

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

            VideoLlmEngineResponse videoLlmEngineResponse;

            if (useVideoLlm) {
                analysisJob.startVideoLlmAnalysis();

                videoLlmEngineResponse = videoLlmEngineClient.analyze(
                        VideoLlmEngineRequest.defaultOption(
                                jobId,
                                uploadedVideo.getStoredFilePath()
                        )
                );
            } else {
                videoLlmEngineResponse = createSkippedVideoLlmResponse(jobId);
            }

            analysisJob.startCompacting();

            Map<String, Object> compactAnalysis = resultCommandService.saveEngineResultsAndCompact(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse
            );

            OpenAiFeedbackResponse openAiFeedbackResponse;

            if (useOpenAi) {
                analysisJob.startOpenAiGenerating();

                openAiFeedbackResponse = openAiClient.generateFeedback(
                        new OpenAiFeedbackRequest(jobId, compactAnalysis)
                );
            } else {
                openAiFeedbackResponse = createSkippedOpenAiResponse(jobId);
            }

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
            saveFailureResultSafely(analysisJob, e.getMessage());
            throw e;
        } catch (Exception e) {
            String failReason = e.getMessage() == null
                    ? "분석 실행 중 알 수 없는 오류가 발생했습니다."
                    : e.getMessage();

            analysisJob.fail(failReason);
            saveFailureResultSafely(analysisJob, failReason);

            throw new BusinessException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "분석 실행 중 오류가 발생했습니다."
            );
        }
    }

    private void validateRunnable(AnalysisJob analysisJob) {
        if (analysisJob.isRunning()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "현재 분석이 진행 중인 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_COMPLETED,
                    "이미 완료된 분석 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (!analysisJob.canRun()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "현재 상태에서는 분석을 실행할 수 없습니다. status=" + analysisJob.getStatus()
            );
        }
    }

    private void validateRetryable(AnalysisJob analysisJob) {
        if (analysisJob.isRunning()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "현재 분석이 진행 중인 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (analysisJob.isCompleted()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_COMPLETED,
                    "이미 완료된 분석 작업입니다. jobId=" + analysisJob.getJobId()
            );
        }

        if (!analysisJob.canRetry()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "실패 상태의 분석 작업만 재시도할 수 있습니다. status=" + analysisJob.getStatus()
            );
        }
    }

    private void saveFailureResultSafely(
            AnalysisJob analysisJob,
            String failReason
    ) {
        try {
            resultCommandService.saveFailureResult(
                    analysisJob.getJobId(),
                    analysisJob.getStatus().name(),
                    failReason
            );
        } catch (Exception ignored) {
            // 실패 결과 저장 중 발생한 예외는 원래 분석 실패 원인을 덮어쓰지 않기 위해 무시합니다.
        }
    }

    private VideoLlmEngineResponse createSkippedVideoLlmResponse(String jobId) {
        return new VideoLlmEngineResponse(
                jobId,
                "skipped",
                Map.of(
                        "name", "video-llm-skipped",
                        "version", "none"
                ),
                Map.of(),
                Map.of(
                        "visualDelivery", "Video LLM 분석을 사용하지 않았습니다.",
                        "mainStrength", "Video LLM 분석 생략",
                        "mainWeakness", "Video LLM 분석 생략"
                )
        );
    }

    private OpenAiFeedbackResponse createSkippedOpenAiResponse(String jobId) {
        return new OpenAiFeedbackResponse(
                jobId,
                "OpenAI 피드백 생성을 사용하지 않았습니다. 기본 분석 결과만 저장되었습니다.",
                List.of(
                        "기본 분석 결과가 정상적으로 생성되었습니다."
                ),
                List.of(
                        "OpenAI 피드백을 활성화하면 더 구체적인 개선점을 받을 수 있습니다."
                ),
                List.of(),
                List.of()
        );
    }
}