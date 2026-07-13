package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.user.TermsVersion;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.JwtBlacklist;
import com.hanium.presentation.global.config.JwtCookieSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtBlacklist jwtBlacklist;

    @BeforeEach
    void setUp() {
        Mockito.reset(jwtBlacklist);
        when(jwtBlacklist.isBlacklisted(anyString())).thenReturn(false);
        userRepository.deleteAll();
    }

    @Test
    void signupLoginAndProtectedEndpointAuthentication() throws Exception {
        Map<String, Object> request = Map.of(
                "email", "user@example.com",
                "password", "password123",
                "agreedToTerms", true
        );

        ResponseEntity<String> signupResponse = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(userRepository.findByEmail("user@example.com")).isPresent();
        var savedUser = userRepository.findByEmail("user@example.com").orElseThrow();
        assertThat(savedUser.getPasswordHash())
                .isNotEqualTo("password123");
        assertThat(savedUser.getTermsAgreedAt()).isNotNull();
        assertThat(savedUser.getTermsVersion()).isEqualTo(TermsVersion.CURRENT);

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
        String loginCookie = findAccessTokenSetCookie(loginResponse);
        assertThat(loginCookie)
                .contains(JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=")
                .contains(accessToken)
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .contains("Max-Age=1800");
        assertThat(loginCookie).doesNotContain("Secure");

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

        HttpHeaders cookieHeaders = new HttpHeaders();
        cookieHeaders.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        ResponseEntity<String> cookieAuthorizedResultsResponse = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(cookieHeaders),
                String.class
        );

        assertThat(cookieAuthorizedResultsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void signupRejectsMissingTermsAgreement() {
        Map<String, Object> request = Map.of(
                "email", "terms-missing@example.com",
                "password", "password123"
        );

        ResponseEntity<String> signupResponse = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findByEmail("terms-missing@example.com")).isEmpty();
    }

    @Test
    void signupRejectsFalseTermsAgreement() {
        Map<String, Object> request = Map.of(
                "email", "terms-false@example.com",
                "password", "password123",
                "agreedToTerms", false
        );

        ResponseEntity<String> signupResponse = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findByEmail("terms-false@example.com")).isEmpty();
    }

    @Test
    void logoutAllowsAnonymousRequestAndInvalidatesCurrentTokenWhenProvided() throws Exception {
        Map<String, Object> request = Map.of(
                "email", "logout@example.com",
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        String accessToken = loginBody.path("data").path("accessToken").asText();

        ResponseEntity<String> logoutWithoutTokenResponse = restTemplate.postForEntity(
                "/api/auth/logout",
                null,
                String.class
        );

        assertThat(logoutWithoutTokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(findAccessTokenSetCookie(logoutWithoutTokenResponse)).contains("Max-Age=0");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> logoutResponse = restTemplate.exchange(
                "/api/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String expiredCookie = findAccessTokenSetCookie(logoutResponse);
        assertThat(expiredCookie)
                .contains(JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/");
        assertThat(expiredCookie).doesNotContain("Secure");
        verify(jwtBlacklist).blacklist(eq(accessToken), org.mockito.ArgumentMatchers.any());

        when(jwtBlacklist.isBlacklisted(accessToken)).thenReturn(true);
        ResponseEntity<String> blacklistedTokenResponse = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(blacklistedTokenResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void meReturnsCurrentUserWithBearerOrCookieAndRejectsAnonymous() throws Exception {
        Map<String, Object> request = Map.of(
                "email", "me@example.com",
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        ResponseEntity<String> anonymousMeResponse = restTemplate.getForEntity(
                "/api/auth/me",
                String.class
        );

        assertThat(anonymousMeResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        String accessToken = loginBody.path("data").path("accessToken").asText();

        HttpHeaders bearerHeaders = new HttpHeaders();
        bearerHeaders.setBearerAuth(accessToken);
        ResponseEntity<String> bearerMeResponse = restTemplate.exchange(
                "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(bearerHeaders),
                String.class
        );

        assertThat(bearerMeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode bearerMeBody = objectMapper.readTree(bearerMeResponse.getBody());
        assertThat(bearerMeBody.path("data").path("id").asLong()).isPositive();
        assertThat(bearerMeBody.path("data").path("email").asText()).isEqualTo("me@example.com");

        HttpHeaders cookieHeaders = new HttpHeaders();
        cookieHeaders.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        ResponseEntity<String> cookieMeResponse = restTemplate.exchange(
                "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(cookieHeaders),
                String.class
        );

        assertThat(cookieMeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode cookieMeBody = objectMapper.readTree(cookieMeResponse.getBody());
        assertThat(cookieMeBody.path("data").path("id").asLong())
                .isEqualTo(bearerMeBody.path("data").path("id").asLong());
        assertThat(cookieMeBody.path("data").path("email").asText()).isEqualTo("me@example.com");
    }

    @Test
    void logoutAcceptsAccessTokenCookieAndExpiresCookie() throws Exception {
        Map<String, Object> request = Map.of(
                "email", "cookie-logout@example.com",
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        String accessToken = loginBody.path("data").path("accessToken").asText();

        HttpHeaders cookieHeaders = new HttpHeaders();
        cookieHeaders.add(HttpHeaders.COOKIE, JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=" + accessToken);
        ResponseEntity<String> logoutResponse = restTemplate.exchange(
                "/api/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(cookieHeaders),
                String.class
        );

        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(findAccessTokenSetCookie(logoutResponse)).contains("Max-Age=0");
        verify(jwtBlacklist).blacklist(eq(accessToken), org.mockito.ArgumentMatchers.any());
    }

    private String findAccessTokenSetCookie(ResponseEntity<String> response) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        return setCookies.stream()
                .filter(cookie -> cookie.startsWith(JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow();
    }
}
