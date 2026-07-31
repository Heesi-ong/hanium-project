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
import com.hanium.presentation.infrastructure.client.openai.OpenAiClient;
import com.hanium.presentation.infrastructure.client.videollm.VideoLlmEngineClient;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전역 QUEUED 백프레셔 검증.
 *
 * <p>dispatch.local-on-run=false로 두어 api/worker 분리 배포를 흉내 냅니다(워커가 느리거나
 * 꺼진 상황과 동일하게, 접수된 작업이 QUEUED로 남고 즉시 실행되지 않습니다). 사용자별 한도는
 * 넉넉하게 두고 전역 한도만 빠듯하게 설정해, 자신의 대기 작업이 하나도 없는 사용자(ownerB)도
 * 시스템 전체 QUEUED 총량이 한도에 닿으면 거절됨을 확인합니다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "analysis.dispatch.local-on-run=false",
        "analysis.queue.max-global-queued=1",
        "analysis.queue.max-queued-per-user=100"
})
class AnalysisQueueBackpressureGlobalIntegrationTest {

    private static final String OWNER_A_JOB_ID = "20260708140000-c3c3c3c3";
    private static final String OWNER_B_JOB_ID = "20260708140001-d4d4d4d4";

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
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        // dispatch.local-on-run=false라 이 테스트가 만든 QUEUED 작업은 이 인스턴스에서 절대
        // 실행되지 않습니다. AsyncAnalysisTestSupport로 "실행 종료"를 기다리면 영원히 QUEUED로
        // 남아 타임아웃됩니다. 대신 여기서 바로 지워, 모든 Spring 테스트 컨텍스트가 공유하는
        // H2(jdbc:h2:mem:presentation)에 좀비 QUEUED 작업이 남아 다른 테스트의
        // awaitAllAnalysisJobsNotRunning()을 타임아웃시키지 않게 합니다.
        cleanUp();
    }

    private void cleanUp() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void otherOwnersRunIsRejectedWhenGlobalQueuedLimitIsReachedByAnotherUser() throws Exception {
        String tokenA = signupAndLogin("queue-global-owner-a@example.com");
        Long ownerAId = findUserId("queue-global-owner-a@example.com");

        String tokenB = signupAndLogin("queue-global-owner-b@example.com");
        Long ownerBId = findUserId("queue-global-owner-b@example.com");

        createUploadedJobWithVideo(OWNER_A_JOB_ID, ownerAId);
        createUploadedJobWithVideo(OWNER_B_JOB_ID, ownerBId);

        ResponseEntity<String> ownerARunResponse = run(tokenA, OWNER_A_JOB_ID);
        assertThat(ownerARunResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        AnalysisJob ownerAJob = analysisJobRepository.findByJobId(OWNER_A_JOB_ID).orElseThrow();
        assertThat(ownerAJob.getStatus()).isEqualTo(AnalysisStatus.QUEUED);

        // ownerB는 자신의 QUEUED 작업이 0건이라 사용자별 한도(100)에는 전혀 걸리지 않지만,
        // 전역 한도(1)를 ownerA가 이미 채워 거절되어야 합니다.
        ResponseEntity<String> ownerBRunResponse = run(tokenB, OWNER_B_JOB_ID);

        assertThat(ownerBRunResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ownerBRunResponse.getBody()).contains("ANALYSIS_QUEUE_FULL");

        AnalysisJob ownerBJob = analysisJobRepository.findByJobId(OWNER_B_JOB_ID).orElseThrow();
        assertThat(ownerBJob.getStatus()).isEqualTo(AnalysisStatus.UPLOADED);
    }

    private ResponseEntity<String> run(String token, String jobId) {
        return restTemplate.exchange(
                "/api/analysis/" + jobId + "/run",
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
}
