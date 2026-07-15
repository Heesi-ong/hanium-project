package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.user.entity.User;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserOnboardingIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
    void completingOnboardingSavesFieldsAndReflectsInMe() throws Exception {
        String token = signupAndLogin("onboarding@example.com", "password123");

        Map<String, Object> onboardingRequest = Map.of(
                "purpose", "PRESENTATION",
                "experienceLevel", "BEGINNER",
                "improvementGoal", "PACE"
        );

        ResponseEntity<String> onboardingResponse = restTemplate.exchange(
                "/api/users/me/onboarding",
                HttpMethod.POST,
                new HttpEntity<>(onboardingRequest, authorizedHeaders(token)),
                String.class
        );
        assertThat(onboardingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        User user = userRepository.findByEmail("onboarding@example.com").orElseThrow();
        assertThat(user.getPurpose()).isEqualTo("PRESENTATION");
        assertThat(user.getExperienceLevel()).isEqualTo("BEGINNER");
        assertThat(user.getImprovementGoal()).isEqualTo("PACE");
        assertThat(user.getOnboardingCompletedAt()).isNotNull();

        ResponseEntity<String> meResponse = restTemplate.exchange(
                "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        JsonNode meBody = objectMapper.readTree(meResponse.getBody());
        assertThat(meBody.path("data").path("onboardingCompleted").asBoolean()).isTrue();
    }

    @Test
    void invalidPurposeIsRejected() throws Exception {
        String token = signupAndLogin("invalid-onboarding@example.com", "password123");

        Map<String, Object> onboardingRequest = Map.of(
                "purpose", "NOT_A_REAL_PURPOSE",
                "experienceLevel", "BEGINNER",
                "improvementGoal", "PACE"
        );

        ResponseEntity<String> onboardingResponse = restTemplate.exchange(
                "/api/users/me/onboarding",
                HttpMethod.POST,
                new HttpEntity<>(onboardingRequest, authorizedHeaders(token)),
                String.class
        );
        assertThat(onboardingResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onboardingRequiresAuthentication() {
        Map<String, Object> onboardingRequest = Map.of(
                "purpose", "PRESENTATION",
                "experienceLevel", "BEGINNER",
                "improvementGoal", "PACE"
        );

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/me/onboarding",
                HttpMethod.POST,
                new HttpEntity<>(onboardingRequest),
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

    private HttpHeaders authorizedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
