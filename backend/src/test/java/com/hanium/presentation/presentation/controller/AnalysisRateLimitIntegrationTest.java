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
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "rate-limit.upload.capacity=2",
        "rate-limit.upload.refill-minutes=1",
        "rate-limit.analysis.capacity=2",
        "rate-limit.analysis.refill-minutes=1"
})
class AnalysisRateLimitIntegrationTest {

    @LocalServerPort
    private int port;

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
    private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @AfterEach
    void tearDown() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
    }

    @Test
    void expensiveAnalysisApisAreRateLimitedPerUser() throws Exception {
        String ownerToken = signupAndLogin("rate-owner@example.com");
        String otherToken = signupAndLogin("rate-other@example.com");

        assertThat(upload(ownerToken, "owner-1.mp4").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload(ownerToken, "owner-2.mp4").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limitedResponse = upload(ownerToken, "owner-3.mp4");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("TOO_MANY_REQUESTS");

        ResponseEntity<String> otherUserResponse = upload(otherToken, "other-1.mp4");

        assertThat(otherUserResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rateLimitWindowCanBeResetAndAllowsRequestsAgain() throws Exception {
        String token = signupAndLogin("rate-reset@example.com");

        assertThat(upload(token, "reset-1.mp4").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload(token, "reset-2.mp4").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload(token, "reset-3.mp4").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        userRateLimiter.resetForTest();

        ResponseEntity<String> afterResetResponse = upload(token, "reset-4.mp4");

        assertThat(afterResetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void uploadRejectsMissingFilePartWithoutServerError() throws Exception {
        String token = signupAndLogin("missing-file@example.com");

        ResponseEntity<String> response = uploadWithoutFile(token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("error").asText()).isEqualTo("INVALID_INPUT_VALUE");
    }

    @Test
    void runAndRetryApisShareAnalysisRateLimitBucket() throws Exception {
        String token = signupAndLogin("rate-analysis@example.com");
        Long ownerId = userRepository.findByEmail("rate-analysis@example.com")
                .map(User::getId)
                .orElseThrow();

        String runJobId1 = "20260703080000-aaaaaaaa";
        String retryJobId = "20260703080001-bbbbbbbb";
        String runJobId2 = "20260703080002-cccccccc";

        createUploadedJob(runJobId1, ownerId);
        createFailedJob(retryJobId, ownerId);
        createUploadedJob(runJobId2, ownerId);

        assertThat(postAnalysis(token, runJobId1, "run").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postAnalysis(token, retryJobId, "retry").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limitedResponse = postAnalysis(token, runJobId2, "run");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("TOO_MANY_REQUESTS");
        AsyncAnalysisTestSupport.awaitJobsNotRunning(
                analysisJobRepository,
                runJobId1,
                retryJobId
        );
    }

    private ResponseEntity<String> upload(String token, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new NamedByteArrayResource(mp4Content(), fileName), fileHeaders(fileName)));

        return restTemplate.exchange(
                "http://localhost:" + port + "/api/analysis/upload",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private ResponseEntity<String> uploadWithoutFile(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        return restTemplate.exchange(
                "http://localhost:" + port + "/api/analysis/upload",
                HttpMethod.POST,
                new HttpEntity<>(new LinkedMultiValueMap<String, Object>(), headers),
                String.class
        );
    }

    private ResponseEntity<String> postAnalysis(String token, String jobId, String action) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        return restTemplate.exchange(
                "http://localhost:" + port + "/api/analysis/" + jobId + "/" + action,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private HttpHeaders fileHeaders(String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("video/mp4"));
        headers.setContentDispositionFormData("file", fileName);
        return headers;
    }

    private byte[] mp4Content() {
        return new byte[]{
                0x00, 0x00, 0x00, 0x18,
                'f', 't', 'y', 'p',
                'i', 's', 'o', 'm',
                0x00, 0x00, 0x00, 0x00,
                0x01, 0x02, 0x03, 0x04
        };
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

    private void createUploadedJob(String jobId, Long ownerId) {
        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                jobId + ".mp4",
                "storage/uploads/" + jobId + "/original.mp4",
                VideoFileType.MP4,
                1024L
        ));
    }

    private void createFailedJob(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.fail("테스트 실패 상태");
        analysisJobRepository.save(analysisJob);
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                jobId + ".mp4",
                "storage/uploads/" + jobId + "/original.mp4",
                VideoFileType.MP4,
                1024L
        ));
    }

    private static class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
