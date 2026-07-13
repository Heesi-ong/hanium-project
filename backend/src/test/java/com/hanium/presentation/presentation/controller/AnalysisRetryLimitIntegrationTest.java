package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.analysis.AnalysisProgressService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "analysis.retry.max-count=1")
class AnalysisRetryLimitIntegrationTest {

    private static final String RETRY_LIMIT_JOB_ID = "20260703120000-abcdef12";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @MockBean
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
    void retryRequestIsRejectedWhenRetryCountExceedsConfiguredLimit() throws Exception {
        String token = signupAndLogin("retry-limit@example.com");
        Long ownerId = userRepository.findByEmail("retry-limit@example.com")
                .map(User::getId)
                .orElseThrow();

        AnalysisJob failedJob = AnalysisJob.create(RETRY_LIMIT_JOB_ID, ownerId);
        failedJob.fail("테스트 실패 상태");
        analysisJobRepository.save(failedJob);

        ResponseEntity<String> acceptedResponse = retry(token, RETRY_LIMIT_JOB_ID);

        assertThat(acceptedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        AsyncAnalysisTestSupport.awaitJobsNotRunning(
                analysisJobRepository,
                RETRY_LIMIT_JOB_ID
        );

        AnalysisJob retriedJob = analysisJobRepository.findByJobId(RETRY_LIMIT_JOB_ID)
                .orElseThrow();
        assertThat(retriedJob.getRetryCount()).isEqualTo(1);
        assertThat(retriedJob.canRetry()).isTrue();

        ResponseEntity<String> rejectedResponse = retry(token, RETRY_LIMIT_JOB_ID);

        assertThat(rejectedResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejectedResponse.getBody()).contains("ANALYSIS_RETRY_LIMIT_EXCEEDED");

        AnalysisJob unchangedJob = analysisJobRepository.findByJobId(RETRY_LIMIT_JOB_ID)
                .orElseThrow();
        assertThat(unchangedJob.getRetryCount()).isEqualTo(1);
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

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
