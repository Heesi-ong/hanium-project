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
 * 사용자별 QUEUED 백프레셔 검증.
 *
 * <p>dispatch.local-on-run=false로 두어 api/worker 분리 배포를 흉내 냅니다. 이 모드에서는 접수된
 * 작업이 QUEUED 상태로 남고 이 인스턴스가 즉시 실행하지 않으므로(워커가 느리거나 꺼진 상황과
 * 동일), 사용자별 한도(analysis.queue.max-queued-per-user)가 실제로 새 요청을 막는지 확인할 수
 * 있습니다. 전역 한도는 넉넉하게 둬 이 테스트가 전역 한도가 아니라 사용자별 한도 때문에
 * 거절되는지 분리해서 검증합니다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "analysis.dispatch.local-on-run=false",
        "analysis.queue.max-global-queued=100",
        "analysis.queue.max-queued-per-user=1"
})
class AnalysisQueueBackpressurePerUserIntegrationTest {

    private static final String FIRST_JOB_ID = "20260708130000-a1a1a1a1";
    private static final String SECOND_JOB_ID = "20260708130001-b2b2b2b2";

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
        // 실행되지 않습니다(워커 폴러도 기본 비활성). AsyncAnalysisTestSupport로 "실행 종료"를
        // 기다리면 영원히 QUEUED로 남아 타임아웃됩니다. 대신 여기서 바로 지워, 모든 Spring
        // 테스트 컨텍스트가 공유하는 H2(jdbc:h2:mem:presentation)에 좀비 QUEUED 작업이 남아
        // 다른 테스트의 awaitAllAnalysisJobsNotRunning()을 타임아웃시키지 않게 합니다.
        cleanUp();
    }

    private void cleanUp() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void secondRunIsRejectedWhenOwnerAlreadyHasMaxQueuedJobs() throws Exception {
        String token = signupAndLogin("queue-per-user@example.com");
        Long ownerId = findUserId("queue-per-user@example.com");

        createUploadedJobWithVideo(FIRST_JOB_ID, ownerId);
        createUploadedJobWithVideo(SECOND_JOB_ID, ownerId);

        ResponseEntity<String> firstRunResponse = run(token, FIRST_JOB_ID);
        assertThat(firstRunResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        AnalysisJob firstJob = analysisJobRepository.findByJobId(FIRST_JOB_ID).orElseThrow();
        assertThat(firstJob.getStatus()).isEqualTo(AnalysisStatus.QUEUED);

        ResponseEntity<String> secondRunResponse = run(token, SECOND_JOB_ID);

        assertThat(secondRunResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(secondRunResponse.getBody()).contains("ANALYSIS_QUEUE_FULL");

        AnalysisJob secondJob = analysisJobRepository.findByJobId(SECOND_JOB_ID).orElseThrow();
        assertThat(secondJob.getStatus()).isEqualTo(AnalysisStatus.UPLOADED);
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
