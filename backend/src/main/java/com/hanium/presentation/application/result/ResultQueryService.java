package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.Map;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ResultQueryService {

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;

    public ResultQueryService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
    }

    @Transactional(readOnly = true)
    public AnalysisResultResponse getFinalResult(String jobId, Long ownerId) {
        validateOwnership(jobId, ownerId);

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);

        Map<String, Object> result = jsonFileStorage.readJson(finalResultPath, Map.class);

        return AnalysisResultResponse.of(jobId, result);
    }

    @Transactional(readOnly = true)
    public Page<ResultSummaryResponse> getResultSummaries(Long ownerId, Pageable pageable) {
        Page<AnalysisJob> analysisJobs = analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(
                ownerId,
                pageable
        );
        Map<String, UploadedVideo> uploadedVideosByJobId = getUploadedVideosByJobId(
                analysisJobs.getContent()
        );

        return analysisJobs.map(analysisJob -> toSummaryResponse(
                analysisJob,
                uploadedVideosByJobId
        ));
    }

    private Map<String, UploadedVideo> getUploadedVideosByJobId(List<AnalysisJob> analysisJobs) {
        List<String> jobIds = analysisJobs.stream()
                .map(AnalysisJob::getJobId)
                .toList();

        if (jobIds.isEmpty()) {
            return Map.of();
        }

        return uploadedVideoRepository.findAllByJobIdIn(jobIds).stream()
                .collect(Collectors.toMap(
                        UploadedVideo::getJobId,
                        Function.identity()
                ));
    }

    private ResultSummaryResponse toSummaryResponse(
            AnalysisJob analysisJob,
            Map<String, UploadedVideo> uploadedVideosByJobId
    ) {
        UploadedVideo uploadedVideo = uploadedVideosByJobId.get(analysisJob.getJobId());
        if (uploadedVideo == null) {
            throw new BusinessException(
                    ErrorCode.FILE_NOT_FOUND,
                    "업로드된 영상 정보를 찾을 수 없습니다."
            );
        }

        Map<String, Object> finalResult = readFinalResultSafely(analysisJob.getJobId());

        return ResultSummaryResponse.of(
                analysisJob,
                uploadedVideo,
                finalResult
        );
    }

    private Map<String, Object> readFinalResultSafely(String jobId) {
        try {
            Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);
            Map<String, Object> finalResult = jsonFileStorage.readJson(finalResultPath, Map.class);

            return finalResult == null ? Map.of() : finalResult;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private void validateOwnership(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }
    }
}
