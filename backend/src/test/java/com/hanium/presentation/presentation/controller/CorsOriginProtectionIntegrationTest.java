package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.JwtCookieSupport;
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

// P2-01의 cookie 인증 경계를 검증한다. CORS 응답 제어에만 의존하지 않고, 인증 쿠키가
// 첨부된 상태 변경 요청은 허용 origin 또는 same-origin Fetch Metadata를 요구한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsOriginProtectionIntegrationTest {

    private static final String ONBOARDING_SKIP_PATH = "/api/users/me/onboarding/skip";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "https://attacker.example.com";

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
    void rejectsCookieAuthenticatedStateChangingRequestFromDisallowedOrigin() throws Exception {
        String accessToken = signupAndLogin("cors-rejected@example.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        headers.set(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange(
                ONBOARDING_SKIP_PATH,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allowsCookieAuthenticatedStateChangingRequestFromAllowedOrigin() throws Exception {
        String accessToken = signupAndLogin("cors-allowed@example.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        headers.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        ResponseEntity<String> response = restTemplate.exchange(
                ONBOARDING_SKIP_PATH,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsBrowserCookieRequestWhenOriginIsMissing() throws Exception {
        String accessToken = signupAndLogin("cors-browser-no-origin@example.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0");

        ResponseEntity<String> response = restTemplate.exchange(
                ONBOARDING_SKIP_PATH,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("AUTH_ORIGIN_FORBIDDEN");
    }

    @Test
    void rejectsSameSiteCrossOriginFetchMetadataWithoutOrigin() throws Exception {
        String accessToken = signupAndLogin("cors-same-site@example.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        headers.set("Sec-Fetch-Site", "same-site");

        ResponseEntity<String> response = restTemplate.exchange(
                ONBOARDING_SKIP_PATH,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Fetch Metadata와 브라우저 User-Agent가 없는 비브라우저 클라이언트는 기존 계약을
    // 유지한다. 브라우저 자동 첨부 쿠키와 달리 공격자가 타 사용자의 쿠키를 주입할 수 없다.
    @Test
    void allowsCookieAuthenticatedStateChangingRequestWithoutOriginHeader() throws Exception {
        String accessToken = signupAndLogin("cors-no-origin@example.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);

        ResponseEntity<String> response = restTemplate.exchange(
                ONBOARDING_SKIP_PATH,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
}
