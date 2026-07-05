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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordComplexityIntegrationTest {

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
    void signupRejectsPasswordWithoutNumber() {
        ResponseEntity<String> response = signup("password-alpha@example.com", "passwordonly");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("영문자와 숫자");
    }

    @Test
    void signupRejectsPasswordWithoutLetter() {
        ResponseEntity<String> response = signup("password-number@example.com", "12345678");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("영문자와 숫자");
    }

    @Test
    void signupAcceptsPasswordWithLetterAndNumber() {
        ResponseEntity<String> response = signup("password-valid@example.com", "password123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> signup(String email, String password) {
        Map<String, String> request = Map.of(
                "email", email,
                "password", password
        );

        return restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );
    }
}
