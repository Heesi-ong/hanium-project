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
        "rate-limit.signup.capacity=2",
        "rate-limit.signup.refill-minutes=10"
})
class SignupRateLimitIntegrationTest {

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
    void signupAttemptsAreRateLimitedByClientIp() {
        assertThat(signup("signup-limit-1@example.com").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(signup("signup-limit-2@example.com").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> limitedResponse = signup("signup-limit-3@example.com");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limitedResponse.getBody()).contains("요청이 너무 많습니다");
    }

    private ResponseEntity<String> signup(String email) {
        Map<String, String> request = Map.of(
                "email", email,
                "password", "password123"
        );

        return restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );
    }
}
