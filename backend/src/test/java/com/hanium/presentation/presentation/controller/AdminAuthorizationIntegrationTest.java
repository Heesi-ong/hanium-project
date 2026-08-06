package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AdminAuthorizationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    // 2026-08-03 이전에는 이 테스트가 "ADMIN_EMAILS와 이메일이 일치하면 공개 회원가입만
    // 으로 즉시 ADMIN이 부여된다"는 것을 정상 동작으로 고정하고 있었다. 이는 관리자
    // 이메일을 아는 누구나 그 주소로 먼저 가입해 관리자 계정을 선점할 수 있는 P0
    // 취약점이었다(실제 재현 확인). 공개 API로는 ADMIN을 만들 수 없어야 한다는 것을
    // 검증하도록 뒤집었다 — 실제 승격 경로(AdminRoleSyncRunner)는
    // AdminRoleSyncRunnerTest에서 별도로 검증한다.
    @Test
    void publicSignupWithAdminListedEmailStaysUser() throws Exception {
        String accessToken = signupAndLogin("admin@example.com", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> pingResponse = restTemplate.exchange(
                "/api/admin/ping",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(pingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonAdminEmailStaysUserAndIsForbiddenFromAdminEndpoint() throws Exception {
        String accessToken = signupAndLogin("member@example.com", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> pingResponse = restTemplate.exchange(
                "/api/admin/ping",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(pingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String signupAndLogin(String email, boolean expectedAdmin) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity("/api/auth/signup", request, String.class);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity("/api/auth/login", request, String.class);
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        assertThat(loginBody.path("data").path("user").path("admin").asBoolean()).isEqualTo(expectedAdmin);

        return loginBody.path("data").path("accessToken").asText();
    }
}
