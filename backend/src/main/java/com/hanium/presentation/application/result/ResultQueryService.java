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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
    public AnalysisResultResponse getFinalResult(String jobId) {
        if (!analysisJobRepository.existsByJobId(jobId)) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND);
        }

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);

        Map<String, Object> result = jsonFileStorage.readJson(finalResultPath, Map.class);

        return AnalysisResultResponse.of(jobId, result);
    }

    @Transactional(readOnly = true)
    public List<ResultSummaryResponse> getResultSummaries() {
        List<AnalysisJob> analysisJobs = analysisJobRepository.findAllByOrderByCreatedAtDesc();

        return analysisJobs.stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    private ResultSummaryResponse toSummaryResponse(AnalysisJob analysisJob) {
        UploadedVideo uploadedVideo = uploadedVideoRepository.findByJobId(analysisJob.getJobId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FILE_NOT_FOUND,
                        "업로드된 영상 정보를 찾을 수 없습니다."
                ));

        return ResultSummaryResponse.of(analysisJob, uploadedVideo);
    }
}