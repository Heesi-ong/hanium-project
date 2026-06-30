package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Map;

@Service
public class ResultQueryService {

    private final AnalysisJobRepository analysisJobRepository;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;

    public ResultQueryService(
            AnalysisJobRepository analysisJobRepository,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage
    ) {
        this.analysisJobRepository = analysisJobRepository;
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
}