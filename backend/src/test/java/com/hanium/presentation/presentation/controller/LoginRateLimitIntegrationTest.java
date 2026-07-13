package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "rate-limit.login.capacity=2",
        "rate-limit.login.refill-minutes=10"
})
class LoginRateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void loginAttemptsAreRateLimitedByEmail() {
        Map<String, Object> signupRequest = Map.of(
                "email", "login-limit@example.com",
                "password", "password123",
                "agreedToTerms", true
        );

        ResponseEntity<String> signupResponse = restTemplate.postForEntity(
                "/api/auth/signup",
                signupRequest,
                String.class
        );

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, String> wrongPasswordRequest = Map.of(
                "email", "login-limit@example.com",
                "password", "wrong-password"
        );

        assertThat(login(wrongPasswordRequest).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(wrongPasswordRequest).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> limitedResponse = login(wrongPasswordRequest);

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("요청이 너무 많습니다");
    }

    private ResponseEntity<String> login(Map<String, String> request) {
        return restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );
    }
}
