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

    @Test
    void adminListedEmailIsPromotedAndCanAccessAdminEndpoint() throws Exception {
        String accessToken = signupAndLogin("admin@example.com", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> pingResponse = restTemplate.exchange(
                "/api/admin/ping",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(pingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
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
