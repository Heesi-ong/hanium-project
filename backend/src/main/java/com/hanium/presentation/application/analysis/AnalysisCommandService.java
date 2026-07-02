package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.AnalysisStep;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
public class AnalysisCommandService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCommandService.class);

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoFileCommandService videoFileCommandService;
    private final ResultCommandService resultCommandService;
    private final AnalysisEngineClient analysisEngineClient;
    private final VideoLlmEngineClient videoLlmEngineClient;
    private final OpenAiClient openAiClient;
    private final JobIdGenerator jobIdGenerator;
    private final AnalysisProgressService analysisProgressService;
    private final AnalysisJobStatusService analysisJobStatusService;
    private final ThreadPoolTaskExecutor analysisTaskExecutor;

    public AnalysisCommandService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            VideoFileCommandService videoFileCommandService,
            ResultCommandService resultCommandService,
            AnalysisEngineClient analysisEngineClient,
            VideoLlmEngineClient videoLlmEngineClient,
            OpenAiClient openAiClient,
            JobIdGenerator jobIdGenerator,
            AnalysisProgressService analysisProgressService,
            AnalysisJobStatusService analysisJobStatusService,
            ThreadPoolTaskExecutor analysisTaskExecutor
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoFileCommandService = videoFileCommandService;
        this.resultCommandService = resultCommandService;
        this.analysisEngineClient = analysisEngineClient;
        this.videoLlmEngineClient = videoLlmEngineClient;
        this.openAiClient = openAiClient;
        this.jobIdGenerator = jobIdGenerator;
        this.analysisProgressService = analysisProgressService;
        this.analysisJobStatusService = analysisJobStatusService;
        this.analysisTaskExecutor = analysisTaskExecutor;
    }

    @Transactional
    public AnalysisUploadResponse uploadVideo(MultipartFile file, Long ownerId) {
        String jobId = jobIdGenerator.generate();

        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
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

    // 분석 실행/재시도는 더 이상 파이프라인이 끝날 때까지 기다리지 않습니다.
    // "시작 상태로 전환하고 커밋"까지만 이 트랜잭션에서 처리하고, 실제 무거운 작업
    // (analysis-engine/video-llm-engine/OpenAI 호출)은 트랜잭션이 커밋된 뒤
    // 백그라운드 스레드에서 실행합니다. 프론트는 이미 /run 호출 후 /status를
    // 폴링하는 구조라 이 변경으로 화면 쪽 코드를 바꿀 필요가 없습니다.
    @Transactional
    public AnalysisStatusResponse runAnalysis(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateRunnable(analysisJob);

        return acceptAndDispatch(analysisJob, useVideoLlm, useOpenAi);
    }

    @Transactional
    public AnalysisStatusResponse retryAnalysis(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        validateRetryable(analysisJob);
        analysisJob.resetForRetry();

        return acceptAndDispatch(analysisJob, useVideoLlm, useOpenAi);
    }

    private AnalysisStatusResponse acceptAndDispatch(
            AnalysisJob analysisJob,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        String jobId = analysisJob.getJobId();

        analysisJob.startBasicAnalysis();

        AnalysisJob savedJob;
        try {
            savedJob = analysisJobRepository.saveAndFlush(analysisJob);
        } catch (OptimisticLockingFailureException e) {
            // 같은 jobId로 거의 동시에 들어온 다른 실행 요청이 먼저 상태를 바꾼 경우입니다.
            throw new BusinessException(
                    ErrorCode.ANALYSIS_ALREADY_RUNNING,
                    "다른 요청이 먼저 분석을 시작했습니다. jobId=" + jobId
            );
        }

        // 지금 트랜잭션이 실제로 커밋된 뒤에만 백그라운드 작업을 시작합니다.
        // 커밋 전에 시작하면, 백그라운드 스레드가 아직 DB에 반영되지 않은
        // (다른 트랜잭션 기준으로는 보이지 않는) 상태를 참조할 수 있습니다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    analysisTaskExecutor.execute(
                            () -> executeAnalysisAsync(jobId, useVideoLlm, useOpenAi)
                    );
                }
            });
        } else {
            // 트랜잭션 없이 호출되는 경우(예: 테스트 코드)를 대비한 안전장치입니다.
            analysisTaskExecutor.execute(() -> executeAnalysisAsync(jobId, useVideoLlm, useOpenAi));
        }

        return AnalysisStatusResponse.from(savedJob);
    }

    // 백그라운드 스레드에서 실행되는 실제 분석 파이프라인입니다.
    // 이 메서드 자체에는 @Transactional을 걸지 않습니다. 각 단계는
    // analysisJobStatusService / resultCommandService 안에서 그때그때 커밋되므로,
    // 여기서 실패해도 이미 커밋된 중간 결과는 그대로 남습니다.
    private void executeAnalysisAsync(
            String jobId,
            boolean useVideoLlm,
            boolean useOpenAi
    ) {
        UploadedVideo uploadedVideo;

        try {
            uploadedVideo = uploadedVideoRepository.findByJobId(jobId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.FILE_NOT_FOUND,
                            "업로드된 영상 정보를 찾을 수 없습니다."
                    ));
        } catch (Exception e) {
            log.error("[{}] 업로드 영상 정보를 찾지 못해 분석을 시작할 수 없습니다.", jobId, e);
            String failReason = "업로드된 영상 정보를 찾을 수 없습니다.";
            analysisJobStatusService.failStatus(jobId, failReason);
            analysisProgressService.fail(jobId, 0, failReason);
            saveFailureResultSafely(jobId, failReason);
            return;
        }

        log.info("[{}] 분석 파이프라인 시작 (useVideoLlm={}, useOpenAi={})", jobId, useVideoLlm, useOpenAi);
        analysisProgressService.start(jobId);

        int lastPercent = 10;

        try {
            log.info("[{}] ({}%) 기본 분석 요청을 analysis-engine으로 전송합니다.", jobId, lastPercent);
            analysisProgressService.update(
                    jobId, AnalysisStep.BASIC_ANALYSIS, AnalysisStatus.BASIC_ANALYZING,
                    lastPercent, "영상/음성 기본 분석을 실행하는 중입니다."
            );

            AnalysisEngineResponse analysisEngineResponse = analysisEngineClient.analyze(
                    new AnalysisEngineRequest(
                            jobId,
                            uploadedVideo.getStoredFilePath()
                    )
            );
            log.info("[{}] 기본 분석 응답을 받았습니다.", jobId);

            VideoLlmEngineResponse videoLlmEngineResponse;

            if (useVideoLlm) {
                analysisJobStatusService.updateStatus(jobId, AnalysisStatus.VIDEO_LLM_ANALYZING);
                lastPercent = 40;
                log.info("[{}] ({}%) Video LLM 분석 요청을 전송합니다.", jobId, lastPercent);
                analysisProgressService.update(
                        jobId, AnalysisStep.VIDEO_LLM_ANALYSIS, AnalysisStatus.VIDEO_LLM_ANALYZING,
                        lastPercent, "Video LLM 분석을 실행하는 중입니다."
                );

                videoLlmEngineResponse = videoLlmEngineClient.analyze(
                        VideoLlmEngineRequest.defaultOption(
                                jobId,
                                uploadedVideo.getStoredFilePath()
                        )
                );
                log.info("[{}] Video LLM 분석 응답을 받았습니다.", jobId);
            } else {
                log.info("[{}] Video LLM 분석을 건너뜁니다. (useVideoLlm=false)", jobId);
                videoLlmEngineResponse = createSkippedVideoLlmResponse(jobId);
            }

            analysisJobStatusService.updateStatus(jobId, AnalysisStatus.COMPACTING);
            lastPercent = 60;
            log.info("[{}] ({}%) 분석 결과를 정리(compact)하는 중입니다.", jobId, lastPercent);
            analysisProgressService.update(
                    jobId, AnalysisStep.COMPACT_ANALYSIS, AnalysisStatus.COMPACTING,
                    lastPercent, "분석 결과를 정리하는 중입니다."
            );

            Map<String, Object> compactAnalysis = resultCommandService.saveEngineResultsAndCompact(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse
            );

            OpenAiFeedbackResponse openAiFeedbackResponse;

            if (useOpenAi) {
                analysisJobStatusService.updateStatus(jobId, AnalysisStatus.OPENAI_GENERATING);
                lastPercent = 75;
                log.info("[{}] ({}%) AI 피드백을 생성하는 중입니다.", jobId, lastPercent);
                analysisProgressService.update(
                        jobId, AnalysisStep.OPENAI_FEEDBACK, AnalysisStatus.OPENAI_GENERATING,
                        lastPercent, "AI 피드백을 생성하는 중입니다."
                );

                openAiFeedbackResponse = openAiClient.generateFeedback(
                        new OpenAiFeedbackRequest(jobId, compactAnalysis)
                );
                log.info("[{}] AI 피드백 생성이 끝났습니다. (mode={})", jobId, openAiFeedbackResponse.generationMode());
            } else {
                log.info("[{}] OpenAI 피드백 생성을 건너뜁니다. (useOpenAi=false)", jobId);
                openAiFeedbackResponse = createSkippedOpenAiResponse(jobId);
            }

            resultCommandService.saveOpenAiFeedbackResult(
                    jobId,
                    openAiFeedbackResponse
            );

            analysisJobStatusService.updateStatus(jobId, AnalysisStatus.MERGING_RESULT);
            lastPercent = 90;
            log.info("[{}] ({}%) 최종 결과를 병합하는 중입니다.", jobId, lastPercent);
            analysisProgressService.update(
                    jobId, AnalysisStep.RESULT_MERGE, AnalysisStatus.MERGING_RESULT,
                    lastPercent, "최종 결과를 병합하는 중입니다."
            );

            resultCommandService.saveFinalResult(
                    jobId,
                    analysisEngineResponse,
                    videoLlmEngineResponse,
                    openAiFeedbackResponse
            );

            analysisJobStatusService.completeStatus(jobId);
            analysisProgressService.complete(jobId);
            log.info("[{}] (100%) 분석 파이프라인이 완료되었습니다.", jobId);
        } catch (BusinessException e) {
            log.warn("[{}] 분석이 실패했습니다: {}", jobId, e.getMessage());
            analysisJobStatusService.failStatus(jobId, e.getMessage());
            analysisProgressService.fail(jobId, lastPercent, e.getMessage());
            saveFailureResultSafely(jobId, e.getMessage());
        } catch (Exception e) {
            String failReason = e.getMessage() == null
                    ? "분석 실행 중 알 수 없는 오류가 발생했습니다."
                    : e.getMessage();

            log.error("[{}] 분석 중 예상하지 못한 오류가 발생했습니다.", jobId, e);
            analysisJobStatusService.failStatus(jobId, failReason);
            analysisProgressService.fail(jobId, lastPercent, failReason);
            saveFailureResultSafely(jobId, failReason);
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
            String jobId,
            String failReason
    ) {
        try {
            resultCommandService.saveFailureResult(
                    jobId,
                    AnalysisStatus.FAILED.name(),
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
