package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "rate-limit.login-ip.capacity=2",
        "rate-limit.login-ip.refill-minutes=10"
})
class LoginIpRateLimitIntegrationTest {

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
    void loginAttemptsAreRateLimitedByClientIpAcrossDifferentEmails() {
        signup("login-ip-limit-1@example.com");
        signup("login-ip-limit-2@example.com");
        signup("login-ip-limit-3@example.com");

        assertThat(login("login-ip-limit-1@example.com").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login("login-ip-limit-2@example.com").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limitedResponse = login("login-ip-limit-3@example.com");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("요청이 너무 많습니다");
    }

    @Test
    void loginIpRateLimitIgnoresSpoofedForwardedForByDefault() {
        signup("login-ip-spoof-1@example.com");
        signup("login-ip-spoof-2@example.com");
        signup("login-ip-spoof-3@example.com");

        assertThat(login("login-ip-spoof-1@example.com", "203.0.113.1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login("login-ip-spoof-2@example.com", "203.0.113.2").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> limitedResponse = login("login-ip-spoof-3@example.com", "203.0.113.3");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("요청이 너무 많습니다");
    }

    private void signup(String email) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/signup",
                createAuthRequest(email),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> login(String email) {
        return restTemplate.postForEntity(
                "/api/auth/login",
                createAuthRequest(email),
                String.class
        );
    }

    private ResponseEntity<String> login(String email, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", forwardedFor);

        return restTemplate.postForEntity(
                "/api/auth/login",
                new HttpEntity<>(createAuthRequest(email), headers),
                String.class
        );
    }

    private Map<String, Object> createAuthRequest(String email) {
        return Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );
    }
}
