package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.analysis.AnalysisProgressService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
import com.hanium.presentation.global.config.JwtCookieSupport;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalysisOwnershipIntegrationTest {

    private static final String STATUS_JOB_ID = "20260702200000-aaaaaaaa";
    private static final String RUN_JOB_ID = "20260702200001-bbbbbbbb";
    private static final String RETRY_JOB_ID = "20260702200002-cccccccc";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @MockitoBean
    private AnalysisProgressService analysisProgressService;

    @BeforeEach
    void setUp() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
    }

    @Test
    void analysisApisOnlyAllowOwnerAccess() throws Exception {
        String ownerToken = signupAndLogin("analysis-owner@example.com");
        String otherToken = signupAndLogin("analysis-other@example.com");

        Long ownerId = userRepository.findByEmail("analysis-owner@example.com")
                .map(User::getId)
                .orElseThrow();

        createUploadedJob(STATUS_JOB_ID, ownerId);
        createUploadedJob(RUN_JOB_ID, ownerId);
        createFailedJob(RETRY_JOB_ID, ownerId);

        when(analysisProgressService.getProgress(STATUS_JOB_ID)).thenReturn(Map.of(
                "jobId", STATUS_JOB_ID,
                "status", "UPLOADED",
                "percent", 25,
                "message", "cached progress"
        ));

        assertForbidden(otherToken, HttpMethod.GET, "/api/analysis/" + STATUS_JOB_ID + "/status");
        assertForbidden(otherToken, HttpMethod.GET, "/api/analysis/" + STATUS_JOB_ID + "/progress");
        verify(analysisProgressService, never()).getProgress(STATUS_JOB_ID);

        assertForbidden(otherToken, HttpMethod.POST, "/api/analysis/" + RUN_JOB_ID + "/run");
        assertForbidden(otherToken, HttpMethod.POST, "/api/analysis/" + RETRY_JOB_ID + "/retry");

        ResponseEntity<String> ownerStatusResponse = exchange(
                ownerToken,
                HttpMethod.GET,
                "/api/analysis/" + STATUS_JOB_ID + "/status"
        );

        assertThat(ownerStatusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerStatusResponse.getBody()).contains(STATUS_JOB_ID);

        clearInvocations(analysisProgressService);
        ResponseEntity<String> ownerProgressResponse = exchange(
                ownerToken,
                HttpMethod.GET,
                "/api/analysis/" + STATUS_JOB_ID + "/progress"
        );

        assertThat(ownerProgressResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerProgressResponse.getBody()).contains("cached progress");
        verify(analysisProgressService).getProgress(STATUS_JOB_ID);

        ResponseEntity<String> ownerRunResponse = exchange(
                ownerToken,
                HttpMethod.POST,
                "/api/analysis/" + RUN_JOB_ID + "/run"
        );

        assertThat(ownerRunResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> ownerRetryResponse = exchange(
                ownerToken,
                HttpMethod.POST,
                "/api/analysis/" + RETRY_JOB_ID + "/retry"
        );

        assertThat(ownerRetryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        AsyncAnalysisTestSupport.awaitJobsNotRunning(
                analysisJobRepository,
                RUN_JOB_ID,
                RETRY_JOB_ID
        );
    }

    private void assertForbidden(
            String token,
            HttpMethod method,
            String path
    ) {
        ResponseEntity<String> response = exchange(token, method, path);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
    }

    private ResponseEntity<String> exchange(
            String token,
            HttpMethod method,
            String path
    ) {
        return restTemplate.exchange(
                path,
                method,
                createAuthorizedEntity(token),
                String.class
        );
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

        return extractAccessTokenFromCookie(loginResponse);
    }

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private void createUploadedJob(String jobId, Long ownerId) {
        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
    }

    private void createFailedJob(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.fail("테스트 실패 상태");
        analysisJobRepository.save(analysisJob);
    }
}
