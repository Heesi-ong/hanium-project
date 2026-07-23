package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.auth.PasswordResetEmailTaskService;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
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

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "admin.emails=admin@example.com")
class AdminPasswordResetEmailTaskIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordResetEmailTaskRepository taskRepository;

    @Autowired
    private PasswordResetEmailTaskService taskService;

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        taskRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void adminCanListDeadLetterWithoutExposingResetLinkOrFullEmail() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        PasswordResetEmailTask deadLetter = createDeadLetter("sensitive-user@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/password-reset-email-tasks/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(response.getBody()).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("id").asLong()).isEqualTo(deadLetter.getId());
        assertThat(content.get(0).path("status").asText()).isEqualTo("DEAD_LETTER");
        assertThat(content.get(0).path("maskedRecipientEmail").asText())
                .isEqualTo("se************@example.com");
        assertThat(response.getBody()).doesNotContain("reset-password?token=");
        assertThat(response.getBody()).doesNotContain("sensitive-user@example.com");
    }

    @Test
    void adminCanRequeueDeadLetterAndAuditAction() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        PasswordResetEmailTask deadLetter = createDeadLetter("requeue-user@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/password-reset-email-tasks/" + deadLetter.getId() + "/requeue",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PasswordResetEmailTask reloaded = taskRepository.findById(deadLetter.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getEncryptedResetLink()).isNotBlank();
        assertThat(auditLogRepository.findAll()).hasSize(1);
    }

    @Test
    void nonAdminCannotAccessPasswordResetEmailTaskEndpoints() throws Exception {
        String memberToken = signupAndLogin("member@example.com");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/password-reset-email-tasks/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private PasswordResetEmailTask createDeadLetter(String email) {
        User user = userRepository.saveAndFlush(User.create(email, "hashed-password"));
        PasswordResetToken token = tokenRepository.saveAndFlush(PasswordResetToken.create(
                user,
                "b".repeat(64),
                LocalDateTime.now().plusMinutes(30)
        ));
        taskService.enqueue(
                user,
                token,
                "https://example.com/reset-password?token=admin-test-secret"
        );
        PasswordResetEmailTask task = taskRepository.findAll().get(0);
        task.markFailedAndScheduleRetry("smtp unavailable", 1, 1, 10);
        return taskRepository.saveAndFlush(task);
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );
        restTemplate.postForEntity("/api/auth/signup", request, String.class);
        ResponseEntity<String> loginResponse =
                restTemplate.postForEntity("/api/auth/login", request, String.class);
        return objectMapper.readTree(loginResponse.getBody()).path("data").path("accessToken").asText();
    }
}
