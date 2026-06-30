package com.hanium.presentation.application.result;

import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

@Service
public class ResultCommandService {

    private final ResultMergeService resultMergeService;
    private final FilePathGenerator filePathGenerator;
    private final JsonFileStorage jsonFileStorage;

    public ResultCommandService(
            ResultMergeService resultMergeService,
            FilePathGenerator filePathGenerator,
            JsonFileStorage jsonFileStorage
    ) {
        this.resultMergeService = resultMergeService;
        this.filePathGenerator = filePathGenerator;
        this.jsonFileStorage = jsonFileStorage;
    }

    public void saveMockFinalResult(String jobId) {
        Map<String, Object> finalResult = resultMergeService.createMockFinalResult(jobId);

        Path finalResultPath = filePathGenerator.generateFinalResultPath(jobId);

        jsonFileStorage.saveJson(finalResultPath, finalResult);
    }
}