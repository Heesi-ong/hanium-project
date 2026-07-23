package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.auth.PasswordResetEmailSender;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// password-reset.enabled=false일 때 요청/확인 API가 계정 존재 여부와 무관하게 항상
// 명확한 비활성 오류를 반환하는지 확인합니다(2026-07-23 코드 리뷰 P1-02).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "password-reset.enabled=false")
class PasswordResetDisabledIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordResetEmailTaskRepository passwordResetEmailTaskRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @MockBean
    private PasswordResetEmailSender passwordResetEmailSender;

    @BeforeEach
    void setUp() {
        passwordResetEmailTaskRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @AfterEach
    void tearDown() {
        // H2가 고정 이름(jdbc:h2:mem:presentation)의 인메모리 DB를 테스트 스위트 전체에서
        // 공유하는 구조상, 이 클래스가 만든 사용자/토큰을 남겨두면 이후 실행되는 다른 테스트
        // 클래스의 무조건적인 userRepository.deleteAll()이 FK 위반으로 실패할 수 있다.
        passwordResetEmailTaskRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void requestReturnsDisabledErrorRegardlessOfWhetherEmailExists() throws Exception {
        signup("disabled-existing@example.com");

        ResponseEntity<String> existingResponse = requestPasswordReset("disabled-existing@example.com");
        ResponseEntity<String> missingResponse = requestPasswordReset("disabled-missing@example.com");

        assertThat(existingResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(missingResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(objectMapper.readTree(existingResponse.getBody()).path("message").asText())
                .isEqualTo(objectMapper.readTree(missingResponse.getBody()).path("message").asText());
        verify(passwordResetEmailSender, never()).sendPasswordResetLink(any(), any());
        assertThat(passwordResetTokenRepository.findAll()).isEmpty();
        assertThat(passwordResetEmailTaskRepository.findAll()).isEmpty();
    }

    @Test
    void confirmReturnsDisabledErrorEvenWithAPreviouslyIssuedValidToken() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/password-reset/confirm",
                Map.of("token", "any-token", "newPassword", "newpassword123"),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private void signup(String email) {
        restTemplate.postForEntity(
                "/api/auth/signup",
                Map.of(
                        "email", email,
                        "password", "password123",
                        "agreedToTerms", true
                ),
                String.class
        );
    }

    private ResponseEntity<String> requestPasswordReset(String email) {
        return restTemplate.postForEntity(
                "/api/auth/password-reset/request",
                Map.of("email", email),
                String.class
        );
    }
}
