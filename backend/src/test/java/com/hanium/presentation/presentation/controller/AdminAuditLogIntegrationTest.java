package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.admin.AdminAuditLogService;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.admin.type.AdminAuditAction;
import com.hanium.presentation.domain.admin.type.AdminAuditTargetType;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
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
        String adminToken = signupAndLogin("admin@example.com");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        adminAuditLogService.record(
                admin.getId(),
                admin.getEmail(),
                AdminAuditAction.SUSPEND_USER,
                AdminAuditTargetType.USER,
                "42",
                "테스트 정지"
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
    }

    @Test
    void adminCanFilterAuditLogsAndInvalidDateRangeIsRejected() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        adminAuditLogService.record(
                admin.getId(),
                admin.getEmail(),
                AdminAuditAction.SUSPEND_USER,
                AdminAuditTargetType.USER,
                "42",
                "정지"
        );
        adminAuditLogService.record(
                admin.getId(),
                admin.getEmail(),
                AdminAuditAction.REQUEUE_STORAGE_DELETION_TASK,
                AdminAuditTargetType.STORAGE_DELETION_TASK,
                "storage-77",
                "재큐잉"
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

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity("/api/auth/signup", request, String.class);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity("/api/auth/login", request, String.class);
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        return loginBody.path("data").path("accessToken").asText();
    }
}
