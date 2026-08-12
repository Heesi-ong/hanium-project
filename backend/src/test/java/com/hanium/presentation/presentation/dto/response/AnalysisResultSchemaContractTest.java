package com.hanium.presentation.presentation.dto.response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.common.contract.ResultSchemaVersion;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalysisResultSchemaContractTest {

    private static final TypeReference<Map<String, Object>> RESULT_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void normalizesUnversionedLegacyFixtureAsVersionZero() throws IOException {
        Map<String, Object> normalized = ResultSummaryResponse.normalizeFinalResult(
                readFixture("legacy-v0.json")
        );

        assertThat(normalized)
                .containsEntry(ResultSchemaVersion.FIELD, ResultSchemaVersion.LEGACY)
                .containsEntry("jobId", "legacy-job");
        assertThat(normalized.get("scoreSummary"))
                .isEqualTo(new ScoreSummary(77, 70, 71, 72, 73, 74, "GOOD"));
    }

    @Test
    void preservesCurrentVersionAndTypedScoreContract() throws IOException {
        Map<String, Object> normalized = ResultSummaryResponse.normalizeFinalResult(
                readFixture("current-v1.json")
        );

        assertThat(normalized)
                .containsEntry(ResultSchemaVersion.FIELD, ResultSchemaVersion.CURRENT)
                .containsEntry("jobId", "current-job");
        assertThat(normalized.get("scoreSummary"))
                .isEqualTo(new ScoreSummary(88, 80, 81, 82, 83, 84, "EXCELLENT"));
    }

    @Test
    void exposesLegacyVersionAtTheStableResponseTopLevel() throws IOException {
        AnalysisJob job = mock(AnalysisJob.class);
        when(job.getJobId()).thenReturn("legacy-job");
        when(job.getStatus()).thenReturn(AnalysisStatus.COMPLETED);
        when(job.getAnalysisKind()).thenReturn(AnalysisKind.STANDARD);

        AnalysisResultResponse response = AnalysisResultResponse.of(
                job,
                readFixture("legacy-v0.json"),
                null
        );

        assertThat(response.resultSchemaVersion()).isEqualTo(ResultSchemaVersion.LEGACY);
        assertThat(response.result())
                .containsEntry(ResultSchemaVersion.FIELD, ResultSchemaVersion.LEGACY);
    }

    @Test
    void marksSynthesizedStatusOnlyResponsesWithTheCurrentVersion() {
        AnalysisJob job = mock(AnalysisJob.class);
        when(job.getJobId()).thenReturn("running-job");
        when(job.getStatus()).thenReturn(AnalysisStatus.QUEUED);
        when(job.getAnalysisKind()).thenReturn(AnalysisKind.STANDARD);

        AnalysisResultResponse response = AnalysisResultResponse.statusOnly(job, null);

        assertThat(response.resultSchemaVersion()).isEqualTo(ResultSchemaVersion.CURRENT);
        assertThat(response.result())
                .containsEntry(ResultSchemaVersion.FIELD, ResultSchemaVersion.CURRENT);
    }

    private Map<String, Object> readFixture(String fileName) throws IOException {
        String path = "/fixtures/results/" + fileName;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("결과 계약 fixture를 찾을 수 없습니다: " + path);
            }
            return objectMapper.readValue(input, RESULT_TYPE);
        }
    }
}
