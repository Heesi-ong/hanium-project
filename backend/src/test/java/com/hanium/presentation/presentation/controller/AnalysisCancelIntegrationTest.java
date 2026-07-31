package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.analysis.AnalysisProgressService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.infrastructure.client.analysis.AnalysisEngineClient;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineResponse;
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "storage.upload-path=${user.dir}/build/test-storage/analysis-cancel/uploads",
        "storage.result-path=${user.dir}/build/test-storage/analysis-cancel/results"
})
class AnalysisCancelIntegrationTest {

    private static final String CANCEL_JOB_ID = "20260703150000-a1b2c3d4";
    private static final String UPLOADED_JOB_ID = "20260703150001-a1b2c3d5";
    private static final String COMPLETED_JOB_ID = "20260703150002-a1b2c3d6";

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

        when(analysisEngineClient.analyze(any(AnalysisEngineRequest.class)))
                .thenAnswer(invocation -> {
                    AnalysisEngineRequest request = invocation.getArgument(0);
                    return analysisResponse(request.jobId());
                });
    }

    @AfterEach
    void tearDown() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
    }

    @Test
    void runningAnalysisCanBeCancelledAndSkipsOpenAiStep() throws Exception {
        String token = signupAndLogin("cancel-owner@example.com");
        Long ownerId = findUserId("cancel-owner@example.com");
        createUploadedJobWithVideo(CANCEL_JOB_ID, ownerId);

        CountDownLatch videoLlmStarted = new CountDownLatch(1);
        CountDownLatch releaseVideoLlm = new CountDownLatch(1);

        when(videoLlmEngineClient.analyze(any(VideoLlmEngineRequest.class)))
                .thenAnswer(invocation -> {
                    VideoLlmEngineRequest request = invocation.getArgument(0);
                    videoLlmStarted.countDown();
                    assertThat(releaseVideoLlm.await(3, TimeUnit.SECONDS)).isTrue();
                    return videoLlmResponse(request.jobId());
                });

        ResponseEntity<String> runResponse = post(token, "/api/analysis/" + CANCEL_JOB_ID + "/run");

        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(videoLlmStarted.await(3, TimeUnit.SECONDS)).isTrue();

        ResponseEntity<String> cancelResponse = post(token, "/api/analysis/" + CANCEL_JOB_ID + "/cancel");

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody()).contains("취소 요청");

        releaseVideoLlm.countDown();
        AsyncAnalysisTestSupport.awaitJobsNotRunning(analysisJobRepository, CANCEL_JOB_ID);

        AnalysisJob cancelledJob = analysisJobRepository.findByJobId(CANCEL_JOB_ID)
                .orElseThrow();
        assertThat(cancelledJob.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(cancelledJob.isCancelRequested()).isTrue();

        verify(openAiClient, never()).generateFeedback(any(OpenAiFeedbackRequest.class));

        @SuppressWarnings("unchecked")
        Map<String, Object> finalResult = jsonFileStorage.readJson(
                filePathGenerator.generateFinalResultPath(CANCEL_JOB_ID),
                Map.class
        );
        assertThat(finalResult.get("status")).isEqualTo("CANCELLED");
        assertThat(finalResult.get("failedStep")).isEqualTo("CANCELLED");
    }

    @Test
    void cancelIsRejectedWhenJobIsNotRunning() throws Exception {
        String token = signupAndLogin("cancel-denied@example.com");
        Long ownerId = findUserId("cancel-denied@example.com");

        createUploadedJobWithVideo(UPLOADED_JOB_ID, ownerId);
        AnalysisJob completedJob = AnalysisJob.create(COMPLETED_JOB_ID, ownerId);
        completedJob.complete();
        analysisJobRepository.save(completedJob);

        ResponseEntity<String> uploadedCancelResponse = post(
                token,
                "/api/analysis/" + UPLOADED_JOB_ID + "/cancel"
        );
        ResponseEntity<String> completedCancelResponse = post(
                token,
                "/api/analysis/" + COMPLETED_JOB_ID + "/cancel"
        );

        assertThat(uploadedCancelResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(uploadedCancelResponse.getBody()).contains("ANALYSIS_CANCEL_NOT_ALLOWED");
        assertThat(completedCancelResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(completedCancelResponse.getBody()).contains("ANALYSIS_CANCEL_NOT_ALLOWED");
    }

    private ResponseEntity<String> post(String token, String path) {
        return restTemplate.exchange(
                path,
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

    private void createUploadedJobWithVideo(String jobId, Long ownerId) {
        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                jobId + ".mp4",
                filePathGenerator.generateOriginalVideoPath(jobId, ".mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
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
}
