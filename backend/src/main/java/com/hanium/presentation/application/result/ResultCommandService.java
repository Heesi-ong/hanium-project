package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Service
public class ResultCommandService {

    private static final Logger log = LoggerFactory.getLogger(ResultCommandService.class);

    private final ResultMergeService resultMergeService;
    private final AnalysisCompactor analysisCompactor;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;
    private final LocalFileStorage localFileStorage;
    private final ObjectStorage objectStorage;
    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;

    public ResultCommandService(
            ResultMergeService resultMergeService,
            AnalysisCompactor analysisCompactor,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage,
            LocalFileStorage localFileStorage,
            ObjectStorage objectStorage,
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository
    ) {
        this.resultMergeService = resultMergeService;
        this.analysisCompactor = analysisCompactor;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
        this.localFileStorage = localFileStorage;
        this.objectStorage = objectStorage;
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
    }

    public void saveAnalysisEngineResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse
    ) {
        Path basicAnalysisPath = filePathGenerator.generateBasicAnalysisPath(jobId);
        jsonFileStorage.saveJson(basicAnalysisPath, analysisEngineResponse);
    }

    public void saveVideoLlmRawResult(
            String jobId,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Path videoLlmRawPath = filePathGenerator.generateVideoLlmRawPath(jobId);
        jsonFileStorage.saveJson(videoLlmRawPath, videoLlmEngineResponse);
    }

    public Map<String, Object> saveVideoLlmCompactResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        Map<String, Object> compactResult = analysisCompactor.compact(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse
        );

        Path videoLlmCompactPath = filePathGenerator.generateVideoLlmCompactPath(jobId);
        jsonFileStorage.saveJson(videoLlmCompactPath, compactResult);

        Path compactAnalysisPath = filePathGenerator.generateCompactAnalysisPath(jobId);
        jsonFileStorage.saveJson(compactAnalysisPath, compactResult);

        return compactResult;
    }

    public void saveOpenAiFeedbackResult(
            String jobId,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Path openAiFeedbackPath = filePathGenerator.generateOpenAiFeedbackPath(jobId);
        jsonFileStorage.saveJson(openAiFeedbackPath, openAiFeedbackResponse);
    }

    public Optional<OpenAiFeedbackResponse> loadExistingRealOpenAiFeedback(String jobId) {
        Path openAiFeedbackPath = filePathGenerator.generateOpenAiFeedbackPath(jobId);

        if (!Files.exists(openAiFeedbackPath)) {
            return Optional.empty();
        }

        try {
            OpenAiFeedbackResponse openAiFeedbackResponse = jsonFileStorage.readJson(
                    openAiFeedbackPath,
                    OpenAiFeedbackResponse.class
            );

            if ("REAL".equals(openAiFeedbackResponse.generationMode())) {
                return Optional.of(openAiFeedbackResponse);
            }
        } catch (BusinessException exception) {
            log.warn(
                    "[{}] 기존 OpenAI 피드백 파일을 읽지 못해 재사용하지 않습니다. path={}, reason={}",
                    jobId,
                    openAiFeedbackPath,
                    exception.getMessage()
            );
        }

        return Optional.empty();
    }

    public void saveFinalResult(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Map<String, Object> finalResult = resultMergeService.createFinalResult(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse,
                openAiFeedbackResponse
        );

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
        jsonFileStorage.saveJson(finalResultPath, finalResult);
    }

    public void saveFailureResult(
            String jobId,
            String failedStep,
            String failReason
    ) {
        Map<String, Object> failureResult = resultMergeService.createFailureResult(
                jobId,
                failedStep,
                failReason
        );

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
        jsonFileStorage.saveJson(finalResultPath, failureResult);
    }

    public Map<String, Object> saveEngineResultsAndCompact(
            String jobId,
            AnalysisEngineResponse analysisEngineResponse,
            VideoLlmEngineResponse videoLlmEngineResponse
    ) {
        saveAnalysisEngineResult(jobId, analysisEngineResponse);
        saveVideoLlmRawResult(jobId, videoLlmEngineResponse);

        return saveVideoLlmCompactResult(
                jobId,
                analysisEngineResponse,
                videoLlmEngineResponse
        );
    }

    @Transactional
    public void deleteResult(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        if (analysisJob.isQueued() || analysisJob.isRunning()) {
            throw new BusinessException(
                    ErrorCode.ANALYSIS_DELETE_NOT_ALLOWED,
                    "진행 중이거나 대기 중인 분석 작업은 삭제할 수 없습니다. 먼저 취소한 뒤 다시 시도해주세요. status=" + analysisJob.getStatus()
            );
        }

        UploadedVideo uploadedVideo = uploadedVideoRepository.findByJobId(jobId)
                .orElse(null);

        Path uploadDirectory = filePathGenerator.generateUploadDirectory(jobId);
        Path resultDirectory = filePathGenerator.generateResultDirectory(jobId);

        localFileStorage.deleteDirectoryIfExists(uploadDirectory);
        localFileStorage.deleteDirectoryIfExists(resultDirectory);
        deleteObjectStoragePrefixQuietly("uploads/" + jobId + "/");
        deleteObjectStoragePrefixQuietly("results/" + jobId + "/");

        if (uploadedVideo != null) {
            uploadedVideoRepository.delete(uploadedVideo);
        }

        analysisJobRepository.delete(analysisJob);
    }

    // 로컬 삭제와 별개로 MinIO에 미러링된 오브젝트도 정리합니다(StorageCleanupService,
    // OriginalVideoRetentionService와 동일한 best-effort 패턴). 이 호출이 실패해도 사용자
    // 요청 삭제/회원탈퇴 자체는 이미 로컬 정리와 DB 삭제가 끝난 뒤이므로 막지 않습니다.
    private void deleteObjectStoragePrefixQuietly(String prefix) {
        try {
            objectStorage.deleteObjectsWithPrefix(prefix);
        } catch (Exception exception) {
            log.warn("OBJECT_STORAGE_RESULT_DELETE_FAILED prefix={} reason={}", prefix, exception.toString());
        }
    }
}
