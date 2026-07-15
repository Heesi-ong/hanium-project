package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.coach.repository.CoachConversationRepository;
import com.hanium.presentation.domain.coach.repository.CoachMessageRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "rate-limit.coach-daily.capacity=2",
        "rate-limit.coach-daily.refill-minutes=1440"
})
class CoachChatIntegrationTest {

    private static final String COMPLETED_JOB_ID = "20260715090000-aaaaaaaa";
    private static final String OTHER_OWNER_JOB_ID = "20260715090001-bbbbbbbb";
    private static final String NOT_COMPLETED_JOB_ID = "20260715090002-cccccccc";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private CoachConversationRepository coachConversationRepository;

    @Autowired
    private CoachMessageRepository coachMessageRepository;

    @Autowired
    private FilePathGenerator filePathGenerator;

    @Autowired
    private JsonFileStorage jsonFileStorage;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() {
        coachMessageRepository.deleteAll();
        coachConversationRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void ownerCanSendMessageAndReadHistoryOnCompletedJob() throws Exception {
        String token = signupAndLogin("coach-owner@example.com");
        Long ownerId = userRepository.findByEmail("coach-owner@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        ResponseEntity<String> emptyHistoryResponse = restTemplate.exchange(
                "/api/results/" + COMPLETED_JOB_ID + "/coach/messages",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        assertThat(emptyHistoryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode emptyHistoryBody = objectMapper.readTree(emptyHistoryResponse.getBody());
        assertThat(emptyHistoryBody.path("data").path("messages")).isEmpty();

        ResponseEntity<String> sendResponse = sendMessage(token, COMPLETED_JOB_ID, "말이 너무 빠른가요?");

        assertThat(sendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sendBody = objectMapper.readTree(sendResponse.getBody());
        JsonNode messages = sendBody.path("data").path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("USER");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("말이 너무 빠른가요?");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).path("generationMode").asText()).isEqualTo("MOCK");
        assertThat(messages.get(1).path("content").asText()).isNotBlank();

        ResponseEntity<String> historyResponse = restTemplate.exchange(
                "/api/results/" + COMPLETED_JOB_ID + "/coach/messages",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        JsonNode historyBody = objectMapper.readTree(historyResponse.getBody());
        assertThat(historyBody.path("data").path("messages")).hasSize(2);
    }

    @Test
    void otherUserCannotSendMessageToOwnersJob() throws Exception {
        String otherToken = signupAndLogin("coach-other@example.com");
        Long ownerId = userRepository.save(User.create(
                        "coach-owner-without-login@example.com",
                        "encoded-password"
                ))
                .getId();
        createCompletedJobFixture(OTHER_OWNER_JOB_ID, ownerId);

        ResponseEntity<String> response = sendMessage(otherToken, OTHER_OWNER_JOB_ID, "질문입니다.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
        assertThat(coachConversationRepository.findByJobId(OTHER_OWNER_JOB_ID)).isEmpty();
    }

    @Test
    void sendingMessageOnNotCompletedJobIsRejected() throws Exception {
        String token = signupAndLogin("coach-not-completed@example.com");
        Long ownerId = userRepository.findByEmail("coach-not-completed@example.com")
                .map(User::getId)
                .orElseThrow();
        analysisJobRepository.save(AnalysisJob.create(NOT_COMPLETED_JOB_ID, ownerId));

        ResponseEntity<String> response = sendMessage(token, NOT_COMPLETED_JOB_ID, "질문입니다.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("완료된 분석 작업에만");
    }

    @Test
    void dailyMessageLimitIsEnforcedPerUser() throws Exception {
        String token = signupAndLogin("coach-limit@example.com");
        Long ownerId = userRepository.findByEmail("coach-limit@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 2").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limitedResponse = sendMessage(token, COMPLETED_JOB_ID, "질문 3");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("AI 코치 일일 메시지 한도");
    }

    private ResponseEntity<String> sendMessage(String token, String jobId, String content) {
        return restTemplate.exchange(
                "/api/results/" + jobId + "/coach/messages",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content", content), authorizedHeaders(token)),
                String.class
        );
    }

    private void createCompletedJobFixture(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.complete();
        analysisJobRepository.save(analysisJob);

        jsonFileStorage.saveJson(
                filePathGenerator.generateCompactAnalysisPath(jobId),
                Map.of(
                        "modelInputs", Map.of(
                                "scoreSummary", Map.of("totalScore", 82)
                        )
                )
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

    private HttpHeaders authorizedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
