package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResultOwnershipIntegrationTest {

    private static final String OWNER_JOB_ID = "20260702180000-aaaaaaaa";
    private static final String OTHER_JOB_ID = "20260702180001-bbbbbbbb";
    private static final String OWNER_SECOND_JOB_ID = "20260702180002-cccccccc";
    private static final String OWNER_THIRD_JOB_ID = "20260702180003-dddddddd";
    private static final String OWNER_PIPELINE_FALLBACK_JOB_ID = "20260702180004-eeeeeeee";
    private static final String OWNER_MISSING_RESULT_JOB_ID = "20260702180005-ffffffff";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UploadedVideoRepository uploadedVideoRepository;

    @Autowired
    private FilePathGenerator filePathGenerator;

    @Autowired
    private JsonFileStorage jsonFileStorage;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void resultApisOnlyAllowOwnerAccess() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com");
        String otherToken = signupAndLogin("other@example.com");

        Long ownerId = userRepository.findByEmail("owner@example.com")
                .map(User::getId)
                .orElseThrow();
        Long otherId = userRepository.findByEmail("other@example.com")
                .map(User::getId)
                .orElseThrow();

        createResultFixture(OWNER_JOB_ID, ownerId, "owner-video.mp4");
        createResultFixture(OTHER_JOB_ID, otherId, "other-video.mp4");

        ResponseEntity<String> otherListResponse = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(otherListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode otherListBody = objectMapper.readTree(otherListResponse.getBody());
        assertThat(otherListBody.path("data").path("content")).hasSize(1);
        JsonNode otherSummary = otherListBody.path("data").path("content").get(0);
        assertThat(otherSummary.path("jobId").asText())
                .isEqualTo(OTHER_JOB_ID);
        assertThat(otherSummary.path("visualAnalysis").path("model").path("generationMode").asText())
                .isEqualTo("MOCK");
        assertThat(otherSummary.path("pipeline").path("videoLlmGenerationMode").asText())
                .isEqualTo("MOCK");
        assertThat(otherSummary.path("pipeline").path("videoLlmAnalysis").asText())
                .isEqualTo("video-llm-engine mock");
        assertThat(otherSummary.path("visualAnalysis").has("observations")).isFalse();

        ResponseEntity<String> deniedGetResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(deniedGetResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deniedGetResponse.getBody()).doesNotContain("owner-video.mp4");

        ResponseEntity<String> deniedDeleteResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.DELETE,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(deniedDeleteResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(analysisJobRepository.existsByJobId(OWNER_JOB_ID)).isTrue();
        assertThat(uploadedVideoRepository.existsByJobId(OWNER_JOB_ID)).isTrue();
        assertThat(Files.exists(filePathGenerator.generateFinalResultPath(OWNER_JOB_ID))).isTrue();

        ResponseEntity<String> ownerGetResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(ownerGetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerGetResponse.getBody()).contains(OWNER_JOB_ID);

        ResponseEntity<String> ownerDeleteResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.DELETE,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(ownerDeleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisJobRepository.existsByJobId(OWNER_JOB_ID)).isFalse();
        assertThat(uploadedVideoRepository.existsByJobId(OWNER_JOB_ID)).isFalse();
    }

    @Test
    void resultListSupportsPagination() throws Exception {
        String ownerToken = signupAndLogin("pagination-owner@example.com");
        Long ownerId = userRepository.findByEmail("pagination-owner@example.com")
                .map(User::getId)
                .orElseThrow();

        createResultFixture(OWNER_JOB_ID, ownerId, "first-video.mp4");
        createResultFixture(OWNER_SECOND_JOB_ID, ownerId, "second-video.mp4");
        createResultFixture(OWNER_THIRD_JOB_ID, ownerId, "third-video.mp4");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results?page=0&size=2",
                HttpMethod.GET,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode responseBody = objectMapper.readTree(response.getBody());
        JsonNode data = responseBody.path("data");

        assertThat(data.path("content")).hasSize(2);
        assertThat(data.path("totalElements").asLong()).isEqualTo(3L);
        assertThat(data.path("number").asInt()).isEqualTo(0);
        assertThat(data.path("size").asInt()).isEqualTo(2);
    }

    @Test
    void resultDetailNormalizesGenerationMetadataWithPipelineFallbacks() throws Exception {
        String ownerToken = signupAndLogin("detail-owner@example.com");
        Long ownerId = userRepository.findByEmail("detail-owner@example.com")
                .map(User::getId)
                .orElseThrow();

        createPipelineFallbackFixture(OWNER_PIPELINE_FALLBACK_JOB_ID, ownerId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results/" + OWNER_PIPELINE_FALLBACK_JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        JsonNode result = body.path("data").path("result");

        assertThat(result.path("feedback").path("generationMode").asText()).isEqualTo("REAL");
        assertThat(result.path("feedback").path("model").asText()).isEqualTo("gpt-4.1-mini");
        assertThat(result.path("feedback").path("realApiUsed").asBoolean()).isTrue();
        assertThat(result.path("visualAnalysis").path("model").path("generationMode").asText())
                .isEqualTo("FALLBACK");
        assertThat(result.path("visualAnalysis").path("model").path("name").asText())
                .isEqualTo("video-llm-engine fallback mock");
        assertThat(result.path("visualAnalysis").has("observations")).isTrue();
        assertThat(body.path("data").path("dataIssue").isNull()).isTrue();
    }

    @Test
    void resultDetailReturnsDataIssueForCompletedJobWithoutResultFile() throws Exception {
        String ownerToken = signupAndLogin("missing-result-owner@example.com");
        Long ownerId = userRepository.findByEmail("missing-result-owner@example.com")
                .map(User::getId)
                .orElseThrow();

        createCompletedJobWithoutResultFile(OWNER_MISSING_RESULT_JOB_ID, ownerId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results/" + OWNER_MISSING_RESULT_JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());

        assertThat(body.path("data").path("dataIssue").asText())
                .isEqualTo("RESULT_DATA_UNAVAILABLE");
        assertThat(body.path("data").path("dataIssueDescription").asText())
                .contains("결과 파일");
        assertThat(body.path("data").path("result").path("feedback").path("generationMode").asText())
                .isEqualTo("UNKNOWN");
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );

        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        return loginBody.path("data").path("accessToken").asText();
    }

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private void createResultFixture(
            String jobId,
            Long ownerId,
            String originalFileName
    ) {
        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                originalFileName,
                Path.of("storage", "uploads", jobId, "original.mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
        jsonFileStorage.saveJson(
                filePathGenerator.generateFinalResultPath(jobId),
                Map.of(
                        "jobId", jobId,
                        "originalFileName", originalFileName,
                        "scoreSummary", Map.of("totalScore", 90),
                        "feedback", Map.of(
                                "generationMode", "MOCK",
                                "overall", "owner scoped feedback"
                        ),
                        "visualAnalysis", Map.of(
                                "model", Map.of(
                                        "name", "mock-video-llm",
                                        "version", "local-mock",
                                        "generationMode", "MOCK"
                                ),
                                "observations", Map.of(
                                        "eyeContact", List.of(Map.of("label", "sample"))
                                )
                        ),
                        "pipeline", Map.of(
                                "videoLlmAnalysis", "video-llm-engine mock",
                                "videoLlmGenerationMode", "MOCK",
                                "openAiGenerationMode", "MOCK",
                                "openAiModel", "-",
                                "openAiRealApiUsed", false,
                                "openAiFallbackReason", "-"
                        )
                )
        );
    }

    private void createPipelineFallbackFixture(
            String jobId,
            Long ownerId
    ) {
        AnalysisJob job = AnalysisJob.create(jobId, ownerId);
        job.complete();
        analysisJobRepository.save(job);
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                "pipeline-fallback.mp4",
                Path.of("storage", "uploads", jobId, "original.mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
        jsonFileStorage.saveJson(
                filePathGenerator.generateFinalResultPath(jobId),
                Map.of(
                        "jobId", jobId,
                        "scoreSummary", Map.of(
                                "totalScore", 88,
                                "level", "A"
                        ),
                        "feedback", Map.of(
                                "generationMode", "UNKNOWN",
                                "model", "-",
                                "realApiUsed", false,
                                "fallbackReason", "-",
                                "overall", "pipeline metadata recovered feedback"
                        ),
                        "visualAnalysis", Map.of(
                                "model", Map.of(
                                        "name", "-",
                                        "generationMode", "UNKNOWN"
                                ),
                                "observations", Map.of(
                                        "eyeContact", List.of(Map.of("label", "sample"))
                                )
                        ),
                        "pipeline", Map.of(
                                "openAiGenerationMode", "REAL",
                                "openAiModel", "gpt-4.1-mini",
                                "openAiRealApiUsed", true,
                                "openAiFallbackReason", "-",
                                "videoLlmAnalysis", "video-llm-engine fallback mock",
                                "videoLlmGenerationMode", "FALLBACK"
                        )
                )
        );
    }

    private void createCompletedJobWithoutResultFile(
            String jobId,
            Long ownerId
    ) {
        AnalysisJob job = AnalysisJob.create(jobId, ownerId);
        job.complete();
        analysisJobRepository.save(job);
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                "missing-result.mp4",
                Path.of("storage", "uploads", jobId, "original.mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
    }
}
