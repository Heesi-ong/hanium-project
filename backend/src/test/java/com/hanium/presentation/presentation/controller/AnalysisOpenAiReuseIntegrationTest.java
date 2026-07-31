package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.analysis.AnalysisProgressService;
import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackResponse;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiFeedbackRequest;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "storage.upload-path=${user.dir}/build/test-storage/openai-reuse/uploads",
        "storage.result-path=${user.dir}/build/test-storage/openai-reuse/results"
})
class AnalysisOpenAiReuseIntegrationTest {

    private static final String REAL_REUSE_JOB_ID = "20260703130000-aaaabbbb";
    private static final String MOCK_RETRY_JOB_ID = "20260703130001-ccccdddd";
    private static final String BROKEN_FILE_JOB_ID = "20260703130002-eeeeffff";

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
    private ResultCommandService resultCommandService;

    @Autowired
    private JsonFileStorage jsonFileStorage;

    @Autowired
    private FilePathGenerator filePathGenerator;

    @MockitoBean
    private AnalysisEngineClient analysisEngineClient;

    @MockitoBean
    private VideoLlmEngineClient videoLlmEngineClient;

    @MockitoBean
    private OpenAiClient openAiClient;

    @MockitoBean
    private AnalysisProgressService analysisProgressService;

    @BeforeEach
    void setUp() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        reset(analysisEngineClient, videoLlmEngineClient, openAiClient, analysisProgressService);
        stubEngineClients();
    }

    @AfterEach
    void tearDown() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
    }

    @Test
    void retryReusesExistingRealOpenAiFeedbackWithoutCallingOpenAiAgain() throws Exception {
        String token = signupAndLogin("openai-reuse-real@example.com");
        Long ownerId = findUserId("openai-reuse-real@example.com");
        createFailedJobWithUploadedVideo(REAL_REUSE_JOB_ID, ownerId);

        resultCommandService.saveOpenAiFeedbackResult(
                REAL_REUSE_JOB_ID,
                realFeedback(REAL_REUSE_JOB_ID, "재사용된 실제 피드백")
        );

        ResponseEntity<String> response = retry(token, REAL_REUSE_JOB_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AsyncAnalysisTestSupport.awaitJobsNotRunning(analysisJobRepository, REAL_REUSE_JOB_ID);

        verify(openAiClient, never()).generateFeedback(any(OpenAiFeedbackRequest.class));
        assertFinalFeedback(REAL_REUSE_JOB_ID, "REAL", "재사용된 실제 피드백");
    }

    @Test
    void retryCallsOpenAiAgainWhenExistingFeedbackIsMock() throws Exception {
        String token = signupAndLogin("openai-reuse-mock@example.com");
        Long ownerId = findUserId("openai-reuse-mock@example.com");
        createFailedJobWithUploadedVideo(MOCK_RETRY_JOB_ID, ownerId);

        resultCommandService.saveOpenAiFeedbackResult(
                MOCK_RETRY_JOB_ID,
                mockFeedback(MOCK_RETRY_JOB_ID, "기존 Mock 피드백")
        );
        when(openAiClient.generateFeedback(any(OpenAiFeedbackRequest.class)))
                .thenReturn(realFeedback(MOCK_RETRY_JOB_ID, "새로 호출한 실제 피드백"));

        ResponseEntity<String> response = retry(token, MOCK_RETRY_JOB_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AsyncAnalysisTestSupport.awaitJobsNotRunning(analysisJobRepository, MOCK_RETRY_JOB_ID);

        verify(openAiClient, times(1)).generateFeedback(any(OpenAiFeedbackRequest.class));
        assertFinalFeedback(MOCK_RETRY_JOB_ID, "REAL", "새로 호출한 실제 피드백");
    }

    @Test
    void retryCallsOpenAiWhenExistingFeedbackFileIsBroken() throws Exception {
        String token = signupAndLogin("openai-reuse-broken@example.com");
        Long ownerId = findUserId("openai-reuse-broken@example.com");
        createFailedJobWithUploadedVideo(BROKEN_FILE_JOB_ID, ownerId);

        Files.createDirectories(filePathGenerator.generateResultDirectory(BROKEN_FILE_JOB_ID));
        Files.writeString(
                filePathGenerator.generateOpenAiFeedbackPath(BROKEN_FILE_JOB_ID),
                "{ broken json"
        );
        when(openAiClient.generateFeedback(any(OpenAiFeedbackRequest.class)))
                .thenReturn(realFeedback(BROKEN_FILE_JOB_ID, "손상 파일 이후 새 피드백"));

        ResponseEntity<String> response = retry(token, BROKEN_FILE_JOB_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AsyncAnalysisTestSupport.awaitJobsNotRunning(analysisJobRepository, BROKEN_FILE_JOB_ID);

        verify(openAiClient, times(1)).generateFeedback(any(OpenAiFeedbackRequest.class));
        assertFinalFeedback(BROKEN_FILE_JOB_ID, "REAL", "손상 파일 이후 새 피드백");
    }

    private void stubEngineClients() {
        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenAnswer(invocation -> {
                    AnalysisEngineRequest request = invocation.getArgument(0);
                    return analysisResponse(request.jobId());
                });

        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenAnswer(invocation -> {
                    VideoLlmEngineRequest request = invocation.getArgument(0);
                    return videoLlmResponse(request.jobId());
                });
    }

    private ResponseEntity<String> retry(String token, String jobId) {
        return restTemplate.exchange(
                "/api/analysis/" + jobId + "/retry",
                HttpMethod.POST,
                createAuthorizedEntity(token),
                String.class
        );
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

    private Long findUserId(String email) {
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow();
    }

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private void createFailedJobWithUploadedVideo(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.fail("테스트 실패 상태");
        analysisJobRepository.save(analysisJob);

        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                jobId + ".mp4",
                filePathGenerator.generateOriginalVideoPath(jobId, ".mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
    }

    @SuppressWarnings("unchecked")
    private void assertFinalFeedback(
            String jobId,
            String expectedGenerationMode,
            String expectedOverall
    ) {
        Map<String, Object> finalResult = jsonFileStorage.readObjectMap(
                filePathGenerator.generateFinalResultPath(jobId)
        );
        Map<String, Object> feedback = (Map<String, Object>) finalResult.get("feedback");

        assertThat(feedback.get("generationMode")).isEqualTo(expectedGenerationMode);
        assertThat(feedback.get("overall")).isEqualTo(expectedOverall);
    }

    private AnalysisEngineResponse analysisResponse(String jobId) {
        return new AnalysisEngineResponse(
                jobId,
                "success",
                Map.of("duration", 10),
                Map.of("fps", 30),
                Map.of("averageVolume", 0.5),
                Map.of("count", 0),
                Map.of("stability", 80),
                Map.of("movement", 70),
                Map.of("detected", true),
                Map.of("dominant", "neutral"),
                Map.of(
                        "totalScore", 82,
                        "postureScore", 80,
                        "gazeScore", 81,
                        "speechScore", 82,
                        "gestureScore", 83,
                        "expressionScore", 84
                ),
                Map.of()
        );
    }

    private VideoLlmEngineResponse videoLlmResponse(String jobId) {
        return new VideoLlmEngineResponse(
                jobId,
                "success",
                Map.of("name", "video-llm-test", "version", "test"),
                Map.of(),
                Map.of(
                        "visualDelivery", "테스트 시각 분석",
                        "mainStrength", "안정적 자세",
                        "mainWeakness", "제스처 다양성"
                )
        );
    }

    private OpenAiFeedbackResponse realFeedback(String jobId, String overallFeedback) {
        return OpenAiFeedbackResponse.real(
                jobId,
                "gpt-test",
                overallFeedback,
                List.of("강점"),
                List.of("개선점"),
                List.of(),
                List.of()
        );
    }

    private OpenAiFeedbackResponse mockFeedback(String jobId, String overallFeedback) {
        return OpenAiFeedbackResponse.mock(
                jobId,
                "gpt-test",
                "test mock",
                overallFeedback,
                List.of("강점"),
                List.of("개선점"),
                List.of(),
                List.of()
        );
    }
}
