package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.repository.StorageDeletionTaskRepository;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.storage.type.StorageDeletionTaskStatus;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 관리자 전용 스토리지 삭제(outbox) DEAD_LETTER 조회·재처리 엔드포인트를 검증합니다.
// (2026-07-23 코드 리뷰 P1-03)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "admin.emails=admin@example.com"
})
class AdminStorageDeletionTaskIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StorageDeletionTaskRepository storageDeletionTaskRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        adminAuditLogRepository.deleteAll();
        storageDeletionTaskRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void adminCanListDeadLetterStorageDeletionTasks() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");

        StorageDeletionTask deadLetterTask = StorageDeletionTask.create(
                "20260723090000-dead0001",
                "uploads/20260723090000-dead0001/",
                StorageDeletionReason.RESULT_DELETE
        );
        deadLetterTask.markFailedAndScheduleRetry("minio down", 1, 2, 240);
        storageDeletionTaskRepository.save(deadLetterTask);

        StorageDeletionTask pendingTask = StorageDeletionTask.create(
                "20260723090100-pend0001",
                "uploads/20260723090100-pend0001/",
                StorageDeletionReason.RESULT_DELETE
        );
        storageDeletionTaskRepository.save(pendingTask);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/storage-deletion-tasks/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("jobId").asText()).isEqualTo("20260723090000-dead0001");
        assertThat(content.get(0).path("status").asText()).isEqualTo("DEAD_LETTER");
        assertThat(content.get(0).path("attemptCount").asInt()).isEqualTo(1);
    }

    @Test
    void adminCanRequeueDeadLetterStorageDeletionTask() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");

        StorageDeletionTask deadLetterTask = StorageDeletionTask.create(
                "20260723090200-dead0002",
                "uploads/20260723090200-dead0002/",
                StorageDeletionReason.ORPHAN_CLEANUP
        );
        deadLetterTask.markFailedAndScheduleRetry("minio down", 1, 2, 240);
        StorageDeletionTask saved = storageDeletionTaskRepository.save(deadLetterTask);
        // 재큐잉 후에도 다음 시도 시각이 과거여야 워커가 바로 집어갈 수 있으므로,
        // 재시도 지연이 눈에 보이도록 일부러 미래로 밀어둔 뒤 재큐잉이 이를 되돌리는지 확인한다.
        ReflectionTestUtils.setField(saved, "nextAttemptAt", LocalDateTime.now().plusHours(1));
        storageDeletionTaskRepository.save(saved);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/storage-deletion-tasks/" + saved.getId() + "/requeue",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 재큐잉"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminAuditLogRepository.findAll()).hasSize(1);

        StorageDeletionTask reloaded = storageDeletionTaskRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StorageDeletionTaskStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getCompletedAt()).isNull();
        assertThat(reloaded.getNextAttemptAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void nonAdminCannotAccessStorageDeletionTaskEndpoints() throws Exception {
        String memberToken = signupAndLogin("member@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/admin/storage-deletion-tasks/dead-letter",
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
