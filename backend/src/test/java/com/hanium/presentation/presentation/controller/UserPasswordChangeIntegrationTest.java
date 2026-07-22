package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.user.repository.UserRepository;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserPasswordChangeIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void changesPasswordAndInvalidatesTheOldOne() throws Exception {
        String email = "password-change@example.com";
        String token = signupAndLogin(email, "password123");

        ResponseEntity<String> changeResponse = restTemplate.exchange(
                "/api/users/me/password",
                HttpMethod.POST,
                createAuthorizedEntity(token, Map.of(
                        "currentPassword", "password123",
                        "newPassword", "newPassword456"
                )),
                String.class
        );

        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 이전 비밀번호로는 더 이상 로그인할 수 없어야 합니다.
        ResponseEntity<String> loginWithOldPassword = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of("email", email, "password", "password123"),
                String.class
        );
        assertThat(loginWithOldPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 새 비밀번호로는 로그인할 수 있어야 합니다.
        ResponseEntity<String> loginWithNewPassword = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of("email", email, "password", "newPassword456"),
                String.class
        );
        assertThat(loginWithNewPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void wrongCurrentPasswordDoesNotChangeAnything() throws Exception {
        String email = "wrong-current@example.com";
        String token = signupAndLogin(email, "password123");

        ResponseEntity<String> changeResponse = restTemplate.exchange(
                "/api/users/me/password",
                HttpMethod.POST,
                createAuthorizedEntity(token, Map.of(
                        "currentPassword", "not-the-real-password",
                        "newPassword", "newPassword456"
                )),
                String.class
        );

        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> loginWithOriginalPassword = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of("email", email, "password", "password123"),
                String.class
        );
        assertThat(loginWithOriginalPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void newPasswordSameAsCurrentIsRejected() throws Exception {
        String email = "same-password@example.com";
        String token = signupAndLogin(email, "password123");

        ResponseEntity<String> changeResponse = restTemplate.exchange(
                "/api/users/me/password",
                HttpMethod.POST,
                createAuthorizedEntity(token, Map.of(
                        "currentPassword", "password123",
                        "newPassword", "password123"
                )),
                String.class
        );

        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void newPasswordMustMeetComplexityRequirements() throws Exception {
        String email = "weak-password@example.com";
        String token = signupAndLogin(email, "password123");

        ResponseEntity<String> changeResponse = restTemplate.exchange(
                "/api/users/me/password",
                HttpMethod.POST,
                createAuthorizedEntity(token, Map.of(
                        "currentPassword", "password123",
                        "newPassword", "onlyletters"
                )),
                String.class
        );

        assertThat(changeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/me/password",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "currentPassword", "password123",
                        "newPassword", "newPassword456"
                )),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String signupAndLogin(String email, String password) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", password,
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

    private HttpEntity<Map<String, Object>> createAuthorizedEntity(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }
}
