package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

@Service
public class ResultCommandService {

    private final ResultMergeService resultMergeService;
    private final AnalysisCompactor analysisCompactor;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;

    public ResultCommandService(
            ResultMergeService resultMergeService,
            AnalysisCompactor analysisCompactor,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage
    ) {
        this.resultMergeService = resultMergeService;
        this.analysisCompactor = analysisCompactor;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
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

        Path compactPath = filePathGenerator.generateVideoLlmCompactPath(jobId);
        jsonFileStorage.saveJson(compactPath, compactResult);

        return compactResult;
    }

    public void saveOpenAiFeedbackResult(
            String jobId,
            OpenAiFeedbackResponse openAiFeedbackResponse
    ) {
        Path openAiFeedbackPath = filePathGenerator.generateOpenAiFeedbackPath(jobId);
        jsonFileStorage.saveJson(openAiFeedbackPath, openAiFeedbackResponse);
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
}