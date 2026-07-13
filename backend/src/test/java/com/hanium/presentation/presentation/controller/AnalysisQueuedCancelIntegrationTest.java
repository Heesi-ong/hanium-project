package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.analysis.AnalysisCommandService;
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
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * QUEUED(대기) 상태 작업의 취소를 검증합니다.
 *
 * <p>dispatch.local-on-run=false로 두어 api/worker 분리 배포를 흉내 냅니다. 이 인스턴스가 /run
 * 직후 곧바로 로컬 executor로 투입하지 않으므로, 접수된 작업이 QUEUED 상태 그대로 남아 취소
 * 시점을 결정적으로(deterministic) 재현할 수 있습니다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "analysis.dispatch.local-on-run=false")
class AnalysisQueuedCancelIntegrationTest {

    private static final String JOB_ID = "20260708150000-e5e5e5e5";

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
    private AnalysisCommandService analysisCommandService;

    @MockBean
    private AnalysisEngineClient analysisEngineClient;

    @MockBean
    private VideoLlmEngineClient videoLlmEngineClient;

    @MockBean
    private OpenAiClient openAiClient;

    @MockBean
    private AnalysisProgressService analysisProgressService;

    @BeforeEach
    void setUp() {
        cleanUp();
        reset(analysisEngineClient, videoLlmEngineClient, openAiClient, analysisProgressService);
    }

    @AfterEach
    void tearDown() {
        // dispatch.local-on-run=false라 이 테스트가 만든 QUEUED 작업은 이 인스턴스에서 실행되지
        // 않습니다. AsyncAnalysisTestSupport로 "실행 종료"를 기다리면 영원히 QUEUED로 남아
        // 타임아웃되므로, 대신 바로 정리해 다른 테스트가 공유하는 H2에 좀비 데이터를 남기지 않습니다.
        cleanUp();
    }

    @Test
    void queuedJobIsCancelledImmediatelyAndStatusIsConsistentAcrossViews() throws Exception {
        String token = signupAndLogin("queued-cancel@example.com");
        Long ownerId = findUserId("queued-cancel@example.com");
        createUploadedJobWithVideo(JOB_ID, ownerId);

        ResponseEntity<String> runResponse = post(token, "/api/analysis/" + JOB_ID + "/run");
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        AnalysisJob queuedJob = analysisJobRepository.findByJobId(JOB_ID).orElseThrow();
        assertThat(queuedJob.getStatus()).isEqualTo(AnalysisStatus.QUEUED);

        ResponseEntity<String> cancelResponse = post(token, "/api/analysis/" + JOB_ID + "/cancel");

        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelResponse.getBody()).contains("\"status\":\"CANCELLED\"");

        // 상태 조회
        ResponseEntity<String> statusResponse = restTemplate.exchange(
                "/api/analysis/" + JOB_ID + "/status",
                HttpMethod.GET,
                createAuthorizedEntity(token),
                String.class
        );
        assertThat(statusResponse.getBody()).contains("\"status\":\"CANCELLED\"");

        // 목록 조회 (owner별 요약 목록에도 같은 상태가 반영되어야 함)
        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/results?page=0&size=10",
                HttpMethod.GET,
                createAuthorizedEntity(token),
                String.class
        );
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).contains(JOB_ID).contains("CANCELLED");

        // 결과 상세 조회 (취소 결과 파일이 저장되어 조회 가능해야 함)
        ResponseEntity<String> resultResponse = restTemplate.exchange(
                "/api/results/" + JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(token),
                String.class
        );
        assertThat(resultResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resultResponse.getBody()).contains("CANCELLED");

        @SuppressWarnings("unchecked")
        Map<String, Object> finalResult = jsonFileStorage.readJson(
                filePathGenerator.generateFinalResultPath(JOB_ID),
                Map.class
        );
        assertThat(finalResult.get("status")).isEqualTo("CANCELLED");

        AnalysisJob cancelledJob = analysisJobRepository.findByJobId(JOB_ID).orElseThrow();
        assertThat(cancelledJob.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(cancelledJob.isCancelRequested()).isFalse();
    }

    @Test
    void workerPollerSkipsJobThatWasCancelledWhileQueued() throws Exception {
        String token = signupAndLogin("queued-cancel-skip@example.com");
        Long ownerId = findUserId("queued-cancel-skip@example.com");
        createUploadedJobWithVideo(JOB_ID, ownerId);

        ResponseEntity<String> runResponse = post(token, "/api/analysis/" + JOB_ID + "/run");
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> cancelResponse = post(token, "/api/analysis/" + JOB_ID + "/cancel");
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        AnalysisJob cancelledJob = analysisJobRepository.findByJobId(JOB_ID).orElseThrow();
        assertThat(cancelledJob.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);

        // QueuedAnalysisJobPoller가 QUEUED 작업을 재투입할 때 호출하는 것과 동일한 경로입니다.
        // claimForExecution()이 상태가 더 이상 QUEUED가 아님을 감지해 선점에 실패해야 합니다.
        analysisCommandService.redispatchQueuedJob(JOB_ID, true, true);

        awaitBackgroundDispatchToSettle();

        AnalysisJob jobAfterRedispatch = analysisJobRepository.findByJobId(JOB_ID).orElseThrow();
        assertThat(jobAfterRedispatch.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(analysisEngineClient, never()).analyze(any(AnalysisEngineRequest.class));
    }

    // redispatchQueuedJob()은 백그라운드 executor에 작업을 투입만 하고 즉시 반환합니다.
    // claimForExecution()이 그 스레드에서 상태를 확인해 선점을 스스로 포기할 시간을 잠깐 기다려줍니다.
    private void awaitBackgroundDispatchToSettle() throws InterruptedException {
        Thread.sleep(Duration.ofMillis(500).toMillis());
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

        restTemplate.postForEntity("/api/auth/signup", request, String.class);

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

    private void cleanUp() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
    }
}
