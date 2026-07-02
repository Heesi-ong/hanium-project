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
class AuthControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void signupLoginAndProtectedEndpointAuthentication() throws Exception {
        Map<String, String> request = Map.of(
                "email", "user@example.com",
                "password", "password123"
        );

        ResponseEntity<String> signupResponse = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(userRepository.findByEmail("user@example.com")).isPresent();
        assertThat(userRepository.findByEmail("user@example.com").orElseThrow().getPasswordHash())
                .isNotEqualTo("password123");

        ResponseEntity<String> duplicateSignupResponse = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        assertThat(duplicateSignupResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        String accessToken = loginBody.path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        ResponseEntity<String> publicHealthResponse = restTemplate.getForEntity(
                "/api/health",
                String.class
        );

        assertThat(publicHealthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> unauthorizedResultsResponse = restTemplate.getForEntity(
                "/api/results",
                String.class
        );

        assertThat(unauthorizedResultsResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> authorizedResultsResponse = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(authorizedResultsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
