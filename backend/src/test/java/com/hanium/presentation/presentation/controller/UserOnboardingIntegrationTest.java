package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.config.JwtCookieSupport;
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
        assertThat(meBody.path("data").path("onboardingSkipped").asBoolean()).isFalse();
        assertThat(meBody.path("data").path("purpose").asText()).isEqualTo("PRESENTATION");
        assertThat(meBody.path("data").path("experienceLevel").asText()).isEqualTo("BEGINNER");
        assertThat(meBody.path("data").path("improvementGoal").asText()).isEqualTo("PACE");
    }

    // 2026-08-06 이전에는 "나중에 하기"가 화면만 넘기고 서버에 아무것도 남기지 않아,
    // 다음 로그인 때마다 다시 온보딩으로 보내졌다(P1-02). 이제는 skip 자체가 서버
    // 상태로 남아 반복 노출을 막는다.
    @Test
    void skippingOnboardingPersistsAndReflectsInMe() throws Exception {
        String token = signupAndLogin("skip-onboarding@example.com", "password123");

        ResponseEntity<String> skipResponse = restTemplate.exchange(
                "/api/users/me/onboarding/skip",
                HttpMethod.POST,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        assertThat(skipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        User user = userRepository.findByEmail("skip-onboarding@example.com").orElseThrow();
        assertThat(user.getOnboardingSkippedAt()).isNotNull();
        assertThat(user.getOnboardingCompletedAt()).isNull();

        ResponseEntity<String> meResponse = restTemplate.exchange(
                "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );
        JsonNode meBody = objectMapper.readTree(meResponse.getBody());
        assertThat(meBody.path("data").path("onboardingSkipped").asBoolean()).isTrue();
        assertThat(meBody.path("data").path("onboardingCompleted").asBoolean()).isFalse();
    }

    @Test
    void completingOnboardingAfterSkipClearsSkippedState() throws Exception {
        String token = signupAndLogin("skip-then-complete@example.com", "password123");

        restTemplate.exchange(
                "/api/users/me/onboarding/skip",
                HttpMethod.POST,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );

        Map<String, Object> onboardingRequest = Map.of(
                "purpose", "LECTURE",
                "experienceLevel", "ADVANCED",
                "improvementGoal", "POSTURE"
        );
        restTemplate.exchange(
                "/api/users/me/onboarding",
                HttpMethod.POST,
                new HttpEntity<>(onboardingRequest, authorizedHeaders(token)),
                String.class
        );

        User user = userRepository.findByEmail("skip-then-complete@example.com").orElseThrow();
        assertThat(user.getOnboardingCompletedAt()).isNotNull();
        assertThat(user.getOnboardingSkippedAt()).isNull();
    }

    @Test
    void skippingAfterCompletionPreservesCompletedStateAndAnswers() throws Exception {
        String token = signupAndLogin("complete-then-skip@example.com", "password123");
        Map<String, Object> onboardingRequest = Map.of(
                "purpose", "LECTURE",
                "experienceLevel", "ADVANCED",
                "improvementGoal", "POSTURE"
        );
        restTemplate.exchange(
                "/api/users/me/onboarding",
                HttpMethod.POST,
                new HttpEntity<>(onboardingRequest, authorizedHeaders(token)),
                String.class
        );

        ResponseEntity<String> skipResponse = restTemplate.exchange(
                "/api/users/me/onboarding/skip",
                HttpMethod.POST,
                new HttpEntity<>(authorizedHeaders(token)),
                String.class
        );

        assertThat(skipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        User user = userRepository.findByEmail("complete-then-skip@example.com").orElseThrow();
        assertThat(user.getOnboardingCompletedAt()).isNotNull();
        assertThat(user.getOnboardingSkippedAt()).isNull();
        assertThat(user.getPurpose()).isEqualTo("LECTURE");
        assertThat(user.getExperienceLevel()).isEqualTo("ADVANCED");
        assertThat(user.getImprovementGoal()).isEqualTo("POSTURE");
    }

    @Test
    void skipOnboardingRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/me/onboarding/skip",
                HttpMethod.POST,
                new HttpEntity<>((Object) null),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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

    private String extractAccessTokenFromCookie(ResponseEntity<String> loginResponse) {
        String setCookieHeader = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String prefix = JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=";
        int start = setCookieHeader.indexOf(prefix) + prefix.length();
        int end = setCookieHeader.indexOf(';', start);
        return end == -1 ? setCookieHeader.substring(start) : setCookieHeader.substring(start, end);
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

        return extractAccessTokenFromCookie(loginResponse);
    }

    private HttpHeaders authorizedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
