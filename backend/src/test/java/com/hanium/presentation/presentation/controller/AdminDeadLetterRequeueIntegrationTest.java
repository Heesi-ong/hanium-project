package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.support.AsyncAnalysisTestSupport;
import com.hanium.presentation.global.config.JwtCookieSupport;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 관리자 전용 DEAD_LETTER(재시도 소진) 조회·재처리 엔드포인트를 검증합니다.
 * async-queue-broker-evaluation.md의 1b(DLQ 격리 + 관리자 재처리) 후속 구현입니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "admin.emails=admin@example.com"
})
class AdminDeadLetterRequeueIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 이전 테스트가 실제로 dispatch한 백그라운드 분석 스레드가 아직 살아있는 채로
        // deleteAll()이 실행되면, 그 스레드가 이미 삭제된 행을 save()하려다
        // StaleObjectStateException을 던져 다음 테스트를 오염시킬 수 있습니다.
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
        adminAuditLogRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @AfterEach
    void tearDown() {
        AsyncAnalysisTestSupport.awaitAllAnalysisJobsNotRunning(analysisJobRepository);
    }

    @Test
    void adminCanListDeadLetterJobs() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob deadLetterJob = AnalysisJob.create("20260717090000-dead0001", member.getId());
        deadLetterJob.enqueue(true, true);
        deadLetterJob.startBasicAnalysis();
        deadLetterJob.deadLetter("엔진 반복 실패");
        analysisJobRepository.save(deadLetterJob);

        AnalysisJob completedJob = AnalysisJob.create("20260717090100-done0001", member.getId());
        completedJob.complete();
        analysisJobRepository.save(completedJob);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/analysis-jobs/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        JsonNode content = body.path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("jobId").asText()).isEqualTo("20260717090000-dead0001");
        assertThat(content.get(0).path("status").asText()).isEqualTo("DEAD_LETTER");
    }

    @Test
    void adminCanRequeueDeadLetterJob() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob deadLetterJob = AnalysisJob.create("20260717090200-dead0002", member.getId());
        deadLetterJob.enqueue(true, false);
        deadLetterJob.startBasicAnalysis();
        deadLetterJob.deadLetter("엔진 반복 실패");
        analysisJobRepository.save(deadLetterJob);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/analysis-jobs/" + deadLetterJob.getJobId() + "/requeue",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 재큐잉"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 감사 로그는 요청 트랜잭션 안에서 동기적으로 기록되므로 즉시 확인할 수 있습니다.
        assertThat(adminAuditLogRepository.findAll()).hasSize(1);

        // 실제 dispatch는 트랜잭션 커밋 후 별도 스레드에서 진행됩니다(analysis-engine이
        // 이 테스트 환경엔 없으므로 결국 다시 실패로 끝나지만, 그 자체가 검증 대상은
        // 아닙니다). 재시도 횟수가 초기화되어 DEAD_LETTER를 벗어났는지만 확인합니다.
        AsyncAnalysisTestSupport.awaitJobsNotRunning(analysisJobRepository, deadLetterJob.getJobId());

        AnalysisJob reloaded = analysisJobRepository.findByJobId(deadLetterJob.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isNotEqualTo(AnalysisStatus.DEAD_LETTER);
        assertThat(reloaded.getRetryCount()).isZero();
    }

    @Test
    void requeuingNonDeadLetterJobReturnsConflict() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob completedJob = AnalysisJob.create("20260717090300-done0002", member.getId());
        completedJob.complete();
        analysisJobRepository.save(completedJob);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/analysis-jobs/" + completedJob.getJobId() + "/requeue",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 재큐잉"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(analysisJobRepository.findByJobId(completedJob.getJobId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisStatus.COMPLETED);
    }

    @Test
    void nonAdminCannotAccessDeadLetterEndpoints() throws Exception {
        String memberToken = signupAndLogin("member@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/admin/analysis-jobs/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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

        ResponseEntity<String> loginResponse = restTemplate.postForEntity("/api/auth/login", request, String.class);
        return extractAccessTokenFromCookie(loginResponse);
    }

    // 공개 signup/login은 더 이상 ADMIN_EMAILS만으로 ADMIN을 부여하지 않는다(2026-08-03
    // P0 수정). 테스트에서 관리자 토큰이 필요하면 가입 후 role을 직접 동기화한다 —
    // JwtAuthenticationFilter가 매 요청마다 DB에서 role을 새로 조회하므로, 이미 발급된
    // 토큰도 이 동기화 이후 즉시 관리자 권한으로 동작한다.
    private String signupAndLoginAsAdmin(String email) throws Exception {
        String token = signupAndLogin(email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.syncRole(UserRole.ADMIN);
        userRepository.save(user);
        return token;
    }
}
