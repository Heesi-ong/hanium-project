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
import com.hanium.presentation.infrastructure.client.openai.OpenAiCoachClient;
import com.hanium.presentation.infrastructure.client.openai.dto.OpenAiCoachReplyResponse;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// CoachChatService.sendMessage()가 OpenAI 호출 도중 DB 트랜잭션(=Hikari 커넥션)을 붙잡고
// 있지 않은지를 검증하는 전용 테스트다. OpenAiCoachClient를 목으로 바꿔치기해 그 호출
// 시점에 실제로 활성 트랜잭션이 없는지를 직접 관찰한다. 다른 테스트(CoachChatIntegrationTest)와
// 같은 클래스에 두면 @MockitoBean이 그쪽의 "실제 mock 응답" 기대와 충돌하므로 별도 컨텍스트로 분리했다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoachChatTransactionBoundaryIntegrationTest {

    private static final String COMPLETED_JOB_ID = "20260716090000-eeeeeeee";

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

    @MockitoBean
    private OpenAiCoachClient openAiCoachClient;

    @BeforeEach
    void setUp() {
        coachMessageRepository.deleteAll();
        coachConversationRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void openAiCallHappensWithNoActiveDbTransaction() throws Exception {
        String token = signupAndLogin("coach-boundary@example.com");
        Long ownerId = userRepository.findByEmail("coach-boundary@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        AtomicBoolean transactionActiveDuringOpenAiCall = new AtomicBoolean(true);
        when(openAiCoachClient.generateReply(any())).thenAnswer(invocation -> {
            transactionActiveDuringOpenAiCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return OpenAiCoachReplyResponse.mock(COMPLETED_JOB_ID, "test-model", "test", "테스트 응답");
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results/" + COMPLETED_JOB_ID + "/coach/messages",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "질문입니다."), authorizedHeaders(token)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 이 값이 true였다면 OpenAI 호출 도중에도 DB 트랜잭션(커넥션)이 여전히 붙잡혀
        // 있었다는 뜻이다 - 원래 결함이 재현된 상태다.
        assertThat(transactionActiveDuringOpenAiCall).isFalse();

        JsonNode body = objectMapper.readTree(response.getBody());
        JsonNode messages = body.path("data").path("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).path("content").asText()).isEqualTo("테스트 응답");
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
