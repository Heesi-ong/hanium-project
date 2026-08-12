package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.admin.AdminAuditLogService;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.config.JwtCookieSupport;
import com.hanium.presentation.global.logging.RequestIdFilter;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "admin.emails=admin@example.com"
})
class AdminAuditLogIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private AdminAuditLogService adminAuditLogService;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        adminAuditLogRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void adminCanReadRecordedAuditLogs() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        adminAuditLogService.record(
                admin.getId(),
                admin.getEmail(),
                AdminAuditAction.SUSPEND_USER,
                AdminAuditTargetType.USER,
                "42",
                "테스트 정지",
                "테스트 계정 어뷰징 신고에 따른 정지",
                "req-test-1",
                "INC-1001"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/audit-logs?page=0&size=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("adminEmail").asText()).isEqualTo("admin@example.com");
        assertThat(content.get(0).path("action").asText()).isEqualTo("SUSPEND_USER");
        assertThat(content.get(0).path("targetId").asText()).isEqualTo("42");
        // P2-03: 파괴적 조치 사유와 상관 ID가 감사로그 조회 응답에 그대로 노출돼야 한다.
        assertThat(content.get(0).path("reason").asText())
                .isEqualTo("테스트 계정 어뷰징 신고에 따른 정지");
        assertThat(content.get(0).path("requestId").asText()).isEqualTo("req-test-1");
        assertThat(content.get(0).path("incidentId").asText()).isEqualTo("INC-1001");
    }

    @Test
    void adminCanFilterAuditLogsAndInvalidDateRangeIsRejected() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        adminAuditLogService.record(
                admin.getId(),
                admin.getEmail(),
                AdminAuditAction.SUSPEND_USER,
                AdminAuditTargetType.USER,
                "42",
                "정지",
                "정지 사유",
                "req-test-2a",
                null
        );
        adminAuditLogService.record(
                admin.getId(),
                admin.getEmail(),
                AdminAuditAction.REQUEUE_STORAGE_DELETION_TASK,
                AdminAuditTargetType.STORAGE_DELETION_TASK,
                "storage-77",
                "재큐잉",
                "재큐잉 사유",
                "req-test-2b",
                null
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> filteredResponse = restTemplate.exchange(
                "/api/admin/audit-logs?adminEmail=ADMIN"
                        + "&action=REQUEUE_STORAGE_DELETION_TASK"
                        + "&targetType=STORAGE_DELETION_TASK"
                        + "&targetId=77",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(filteredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode filtered = objectMapper.readTree(filteredResponse.getBody()).path("data").path("content");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("action").asText())
                .isEqualTo("REQUEUE_STORAGE_DELETION_TASK");
        assertThat(filtered.get(0).path("targetId").asText()).isEqualTo("storage-77");

        ResponseEntity<String> invalidRangeResponse = restTemplate.exchange(
                "/api/admin/audit-logs?from=2026-08-02T00:00:00&to=2026-08-01T00:00:00",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(invalidRangeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(invalidRangeResponse.getBody()).path("message").asText())
                .contains("시작 시각은 종료 시각보다 늦을 수 없습니다");
    }

    @Test
    void destructiveActionStoresTheHttpRequestIdUsedByLogsAndResponse() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        signupAndLogin("audit-target@example.com");
        User target = userRepository.findByEmail("audit-target@example.com").orElseThrow();
        String requestId = "admin-action-request-123";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.set(RequestIdFilter.REQUEST_ID_HEADER, requestId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users/" + target.getId() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "반복적인 서비스 악용 확인"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo(requestId);
        assertThat(adminAuditLogRepository.findAll())
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getAction()).isEqualTo(AdminAuditAction.SUSPEND_USER);
                    assertThat(log.getRequestId()).isEqualTo(requestId);
                });
    }

    @Test
    void everyDestructiveAdminEndpointRejectsBlankReasonBeforeExecutingTheAction() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        List<DestructiveEndpoint> endpoints = List.of(
                new DestructiveEndpoint(HttpMethod.POST, "/api/admin/users/999999/suspend"),
                new DestructiveEndpoint(HttpMethod.POST, "/api/admin/users/999999/withdraw"),
                new DestructiveEndpoint(HttpMethod.DELETE, "/api/admin/results/20260812000000-deadbeef"),
                new DestructiveEndpoint(HttpMethod.POST, "/api/admin/analysis-jobs/20260812000000-deadbeef/requeue"),
                new DestructiveEndpoint(HttpMethod.POST, "/api/admin/storage-deletion-tasks/999999/requeue"),
                new DestructiveEndpoint(HttpMethod.POST, "/api/admin/password-reset-email-tasks/999999/requeue")
        );

        for (DestructiveEndpoint endpoint : endpoints) {
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint.path(),
                    endpoint.method(),
                    new HttpEntity<>(Map.of("reason", "   "), headers),
                    String.class
            );

            assertThat(response.getStatusCode())
                    .as("%s %s", endpoint.method(), endpoint.path())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        assertThat(adminAuditLogRepository.count()).isZero();
    }

    @Test
    void nonAdminCannotReadAuditLogs() throws Exception {
        String memberToken = signupAndLogin("member@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/audit-logs",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String extractAccessTokenFromCookie(ResponseEntity<String> loginResponse) {
        String setCookieHeader = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String prefix = JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=";
        int start = setCookieHeader.indexOf(prefix) + prefix.length();
        int end = setCookieHeader.indexOf(';', start);
        return end == -1 ? setCookieHeader.substring(start) : setCookieHeader.substring(start, end);
    }

    private record DestructiveEndpoint(HttpMethod method, String path) {
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
