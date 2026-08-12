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
import com.hanium.presentation.global.config.JwtCookieSupport;
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

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResultMemoIntegrationTest {

    private static final String OWNER_JOB_ID = "20260722190000-aaaaaaaa";
    private static final String OTHER_OWNER_JOB_ID = "20260722190001-bbbbbbbb";

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
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void ownerCanSetAndClearMemo() throws Exception {
        String token = signupAndLogin("memo-owner@example.com");
        Long ownerId = userRepository.findByEmail("memo-owner@example.com")
                .map(User::getId)
                .orElseThrow();
        createResultFixture(OWNER_JOB_ID, ownerId, "owner-video.mp4");

        ResponseEntity<String> updateResponse = updateMemo(token, OWNER_JOB_ID, "1차 리허설");

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updateBody = objectMapper.readTree(updateResponse.getBody());
        assertThat(updateBody.path("message").asText()).isEqualTo("메모가 저장되었습니다.");

        JsonNode listBody = fetchResultList(token);
        assertThat(listBody.path("content").get(0).path("memo").asText()).isEqualTo("1차 리허설");

        assertThat(updateMemo(token, OWNER_JOB_ID, "  ").getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode clearedListBody = fetchResultList(token);
        assertThat(clearedListBody.path("content").get(0).path("memo").isNull()).isTrue();
    }

    @Test
    void otherUserCannotUpdateOwnersMemo() throws Exception {
        String otherToken = signupAndLogin("memo-other@example.com");
        Long ownerId = userRepository.save(User.create(
                        "memo-owner-without-login@example.com",
                        "encoded-password"
                ))
                .getId();
        createResultFixture(OTHER_OWNER_JOB_ID, ownerId, "other-video.mp4");

        ResponseEntity<String> response = updateMemo(otherToken, OTHER_OWNER_JOB_ID, "무단 수정 시도");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
        assertThat(analysisJobRepository.findByJobId(OTHER_OWNER_JOB_ID).orElseThrow().getMemo()).isNull();
    }

    @Test
    void memoExceedingMaxLengthIsRejected() throws Exception {
        String token = signupAndLogin("memo-too-long@example.com");
        Long ownerId = userRepository.findByEmail("memo-too-long@example.com")
                .map(User::getId)
                .orElseThrow();
        createResultFixture(OWNER_JOB_ID, ownerId, "owner-video.mp4");

        String tooLongMemo = "가".repeat(201);
        ResponseEntity<String> response = updateMemo(token, OWNER_JOB_ID, tooLongMemo);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(analysisJobRepository.findByJobId(OWNER_JOB_ID).orElseThrow().getMemo()).isNull();
    }

    @Test
    void updatingMemoForNonexistentJobReturnsNotFound() throws Exception {
        String token = signupAndLogin("memo-missing-job@example.com");

        ResponseEntity<String> response = updateMemo(token, "20260722190099-ffffffff", "메모");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_NOT_FOUND");
    }

    private ResponseEntity<String> updateMemo(String token, String jobId, String memo) {
        return restTemplate.exchange(
                "/api/results/" + jobId + "/memo",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("memo", memo), authorizedHeaders(token)),
                String.class
        );
    }

    private JsonNode fetchResultList(String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );

        return objectMapper.readTree(response.getBody()).path("data");
    }

    private void createResultFixture(String jobId, Long ownerId, String originalFileName) {
        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                originalFileName,
                Path.of("storage", "uploads", jobId, "original.mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
    }

    private String extractAccessTokenFromCookie(ResponseEntity<String> loginResponse) {
        String setCookieHeader = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String prefix = JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=";
        int start = setCookieHeader.indexOf(prefix) + prefix.length();
        int end = setCookieHeader.indexOf(';', start);
        return end == -1 ? setCookieHeader.substring(start) : setCookieHeader.substring(start, end);
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

        return extractAccessTokenFromCookie(loginResponse);
    }

    private HttpHeaders authorizedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
