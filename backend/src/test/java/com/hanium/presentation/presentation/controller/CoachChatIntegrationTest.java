package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
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
import org.springframework.test.util.ReflectionTestUtils;

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
    private static final String MISSING_COMPACT_JOB_ID = "20260715090003-dddddddd";

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

    // loadHistorySummary()/readScoreSummary()가 실제 Spring/JPA/파일 저장소 위에서 올바르게
    // 동작하는지 확인합니다: 같은 사용자의 다른 완료된 STANDARD 발표(점수 이력에 포함되어야
    // 함)와 VIDEO_LLM_REANALYSIS 발표(제외되어야 함)가 섞여 있어도 메시지 전송이 정상
    // 동작해야 합니다. OpenAI가 비활성화된 테스트 환경이라 실제로 어떤 프롬프트가 전송됐는지는
    // 여기서 검증하지 않고(CoachPromptBuilderTest/OpenAiCoachClientTest가 담당), 배선 자체가
    // 깨지지 않는지만 확인합니다.
    @Test
    void sendMessageSucceedsWhenOwnerHasPastCompletedAndReanalysisJobs() throws Exception {
        String token = signupAndLogin("coach-history-owner@example.com");
        Long ownerId = userRepository.findByEmail("coach-history-owner@example.com")
                .map(User::getId)
                .orElseThrow();

        String pastStandardJobId = "20260701090000-11111111";
        String pastReanalysisJobId = "20260702090000-22222222";
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);
        createCompletedJobFixtureWithFinalResult(pastStandardJobId, ownerId, 74);
        createCompletedReanalysisJobFixture(pastReanalysisJobId, ownerId);

        ResponseEntity<String> sendResponse = sendMessage(token, COMPLETED_JOB_ID, "저번보다 나아졌나요?");

        assertThat(sendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sendBody = objectMapper.readTree(sendResponse.getBody());
        assertThat(sendBody.path("data").path("messages")).hasSize(2);
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
    void sendingMessageOnCompletedJobWithoutCompactAnalysisIsRejectedWithoutCreatingConversation() throws Exception {
        String token = signupAndLogin("coach-missing-compact@example.com");
        Long ownerId = userRepository.findByEmail("coach-missing-compact@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobWithoutCompactAnalysis(MISSING_COMPACT_JOB_ID, ownerId);

        ResponseEntity<String> response = sendMessage(token, MISSING_COMPACT_JOB_ID, "질문입니다.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("AI 코치에 필요한 분석 요약 데이터");
        assertThat(coachConversationRepository.findByJobId(MISSING_COMPACT_JOB_ID)).isEmpty();
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

    @Test
    void messageHistoryIncludesDailyUsageThatIncrementsAsMessagesAreSent() throws Exception {
        String token = signupAndLogin("coach-usage@example.com");
        Long ownerId = userRepository.findByEmail("coach-usage@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        ResponseEntity<String> initialHistoryResponse = restTemplate.exchange(
                "/api/results/" + COMPLETED_JOB_ID + "/coach/messages",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        JsonNode initialUsage = objectMapper.readTree(initialHistoryResponse.getBody())
                .path("data").path("dailyUsage");
        assertThat(initialUsage.path("used").asLong()).isEqualTo(0L);
        assertThat(initialUsage.path("capacity").asLong()).isEqualTo(2L);
        assertThat(initialUsage.path("remaining").asLong()).isEqualTo(2L);

        JsonNode afterSendUsage = objectMapper.readTree(
                        sendMessage(token, COMPLETED_JOB_ID, "질문 1").getBody()
                )
                .path("data").path("dailyUsage");
        assertThat(afterSendUsage.path("used").asLong()).isEqualTo(1L);
        assertThat(afterSendUsage.path("remaining").asLong()).isEqualTo(1L);

        // 한도(2개)를 초과해 429가 발생해도, 해당 사용자의 사용량 자체는 계속 조회할 수 있어야 합니다.
        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 2").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 3").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        ResponseEntity<String> finalHistoryResponse = restTemplate.exchange(
                "/api/results/" + COMPLETED_JOB_ID + "/coach/messages",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        JsonNode finalUsage = objectMapper.readTree(finalHistoryResponse.getBody())
                .path("data").path("dailyUsage");
        // 한도를 초과해 거절된 시도도 Redis 카운터 자체는 증가시키므로(공유 INCR 카운터),
        // 성공 2회 + 거절 1회로 used는 3이 되고 remaining은 0으로 클램프됩니다.
        assertThat(finalUsage.path("used").asLong()).isEqualTo(3L);
        assertThat(finalUsage.path("remaining").asLong()).isEqualTo(0L);
    }

    @Test
    void ownerCanResetConversationAndHistoryBecomesEmpty() throws Exception {
        String token = signupAndLogin("coach-reset-owner@example.com");
        Long ownerId = userRepository.findByEmail("coach-reset-owner@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 1").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resetResponse = resetConversation(token, COMPLETED_JOB_ID);

        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode resetBody = objectMapper.readTree(resetResponse.getBody());
        assertThat(resetBody.path("message").asText()).isEqualTo("대화가 초기화되었습니다.");

        ResponseEntity<String> historyResponse = restTemplate.exchange(
                "/api/results/" + COMPLETED_JOB_ID + "/coach/messages",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        JsonNode historyBody = objectMapper.readTree(historyResponse.getBody());
        assertThat(historyBody.path("data").path("messages")).isEmpty();

        // 초기화 후에도 대화(CoachConversation) 자체는 남아있어 다음 메시지를 그대로 이어받습니다.
        assertThat(coachConversationRepository.findByJobId(COMPLETED_JOB_ID)).isPresent();
    }

    @Test
    void resettingConversationDoesNotResetTheDailyMessageLimit() throws Exception {
        String token = signupAndLogin("coach-reset-limit@example.com");
        Long ownerId = userRepository.findByEmail("coach-reset-limit@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendMessage(token, COMPLETED_JOB_ID, "질문 2").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resetConversation(token, COMPLETED_JOB_ID).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limitedResponse = sendMessage(token, COMPLETED_JOB_ID, "질문 3");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void resettingConversationWithoutAnyMessagesIsANoOp() throws Exception {
        String token = signupAndLogin("coach-reset-empty@example.com");
        Long ownerId = userRepository.findByEmail("coach-reset-empty@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedJobFixture(COMPLETED_JOB_ID, ownerId);

        ResponseEntity<String> resetResponse = resetConversation(token, COMPLETED_JOB_ID);

        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void otherUserCannotResetOwnersConversation() throws Exception {
        String otherToken = signupAndLogin("coach-reset-other@example.com");
        Long ownerId = userRepository.save(User.create(
                        "coach-reset-owner-without-login@example.com",
                        "encoded-password"
                ))
                .getId();
        createCompletedJobFixture(OTHER_OWNER_JOB_ID, ownerId);

        ResponseEntity<String> response = resetConversation(otherToken, OTHER_OWNER_JOB_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
    }

    private ResponseEntity<String> sendMessage(String token, String jobId, String content) {
        return restTemplate.exchange(
                "/api/results/" + jobId + "/coach/messages",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content", content), authorizedHeaders(token)),
                String.class
        );
    }

    private ResponseEntity<String> resetConversation(String token, String jobId) {
        return restTemplate.exchange(
                "/api/results/" + jobId + "/coach/messages",
                HttpMethod.DELETE,
                new HttpEntity<>(authorizedHeaders(token)),
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

    // 과거 발표 이력용 fixture: final-result.json에 scoreSummary를 심어둡니다
    // (readScoreSummary()가 참고하는 파일).
    private void createCompletedJobFixtureWithFinalResult(String jobId, Long ownerId, int totalScore) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.complete();
        analysisJobRepository.save(analysisJob);

        jsonFileStorage.saveJson(
                filePathGenerator.generateFinalResultPath(jobId),
                Map.of("scoreSummary", Map.of("totalScore", totalScore))
        );
    }

    // 과거 발표 이력에서 제외돼야 하는 재분석(VIDEO_LLM_REANALYSIS) 발표 fixture입니다.
    // createVideoLlmReanalysis()의 소스 job 전제조건(FALLBACK 결과 등)을 모두 갖추는 대신,
    // 완료된 STANDARD job을 만든 뒤 analysisKind만 직접 바꿔 이 쿼리가 보는 필드만 검증합니다.
    private void createCompletedReanalysisJobFixture(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.complete();
        ReflectionTestUtils.setField(analysisJob, "analysisKind", AnalysisKind.VIDEO_LLM_REANALYSIS);
        analysisJobRepository.save(analysisJob);

        jsonFileStorage.saveJson(
                filePathGenerator.generateFinalResultPath(jobId),
                Map.of("scoreSummary", Map.of("totalScore", 99))
        );
    }

    private void createCompletedJobWithoutCompactAnalysis(String jobId, Long ownerId) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, ownerId);
        analysisJob.complete();
        analysisJobRepository.save(analysisJob);
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
