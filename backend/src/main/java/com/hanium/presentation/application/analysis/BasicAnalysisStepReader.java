package com.hanium.presentation.application.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 분석 엔진이 공유 {@code /storage} 볼륨에 남기는 기본 분석 세부 단계
 * ({@code temp/{jobId}/progress.json})를 읽습니다.
 *
 * <p>백엔드는 분석 엔진을 한 번의 동기 호출로 기다리므로, 엔진이 내부 9단계를 도는 "동안"의
 * 진행 상황을 이 파일로만 알 수 있습니다. 파일이 없거나 깨졌으면 빈 값을 돌려주고, 호출부는
 * 기존 파이프라인 단계 기준 진행률을 그대로 사용합니다.</p>
 */
@Service
public class BasicAnalysisStepReader {

    private static final Logger log = LoggerFactory.getLogger(BasicAnalysisStepReader.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final FilePathGenerator filePathGenerator;
    private final ObjectMapper objectMapper;

    public BasicAnalysisStepReader(
            FilePathGenerator filePathGenerator,
            ObjectMapper objectMapper
    ) {
        this.filePathGenerator = filePathGenerator;
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, Object>> read(String jobId) {
        Path progressPath = filePathGenerator.generateBasicProgressPath(jobId);

        if (!Files.exists(progressPath)) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(objectMapper.readValue(progressPath.toFile(), MAP_TYPE));
        } catch (IOException exception) {
            log.debug("[{}] progress.json 읽기를 건너뜁니다: {}", jobId, exception.toString());
            return Optional.empty();
        }
    }
}
