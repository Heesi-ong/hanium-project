package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.application.auth.PasswordResetEmailSender;
import com.hanium.presentation.domain.user.TermsVersion;
import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.JwtBlacklist;
import com.hanium.presentation.global.config.JwtCookieSupport;
import com.hanium.presentation.global.config.UserRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
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
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtBlacklist jwtBlacklist;

    @MockBean
    private PasswordResetEmailSender passwordResetEmailSender;

    @BeforeEach
    void setUp() {
        Mockito.reset(jwtBlacklist, passwordResetEmailSender);
        when(jwtBlacklist.isBlacklisted(anyString())).thenReturn(false);
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        passwordResetTokenRepository.deleteAll();
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
        assertThat(savedUser.getPasswordChangedAt()).isNull();

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
    void loginRejectsUnsupportedContentTypeWithoutServerError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>("email=user@example.com&password=password123", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("error").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE_ERROR");
    }

    @Test
    void loginRejectsMalformedJsonWithoutServerError() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>("{\"email\":\"user@example.com\",", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("error").asText()).isEqualTo("INVALID_INPUT_VALUE");
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
    void protectedEndpointRejectsInvalidQueryParameterWithoutServerError() throws Exception {
        Map<String, Object> request = Map.of(
                "email", "query-error@example.com",
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

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results?page=not-a-number",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("error").asText()).isEqualTo("INVALID_INPUT_VALUE");
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

    @Test
    void passwordResetRequestDoesNotRevealWhetherEmailExists() throws Exception {
        signup("reset-existing@example.com", "password123");

        ResponseEntity<String> existingResponse = requestPasswordReset("reset-existing@example.com");
        ResponseEntity<String> missingResponse = requestPasswordReset("reset-missing@example.com");

        assertThat(existingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(missingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(existingResponse.getBody()).path("message").asText())
                .isEqualTo(objectMapper.readTree(missingResponse.getBody()).path("message").asText());
        verify(passwordResetEmailSender).sendPasswordResetLink(
                org.mockito.ArgumentMatchers.any(User.class),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void passwordResetConfirmChangesPasswordOnceAndStoresOnlyTokenHash() throws Exception {
        signup("reset-confirm@example.com", "password123");
        ResponseEntity<String> preResetLoginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of(
                        "email", "reset-confirm@example.com",
                        "password", "password123"
                ),
                String.class
        );
        String preResetAccessToken = objectMapper.readTree(preResetLoginResponse.getBody())
                .path("data")
                .path("accessToken")
                .asText();

        requestPasswordReset("reset-confirm@example.com");
        String resetLink = capturePasswordResetLink();
        String token = resetLink.substring(resetLink.indexOf("token=") + "token=".length());

        PasswordResetToken savedToken = passwordResetTokenRepository.findAll().get(0);
        assertThat(savedToken.getTokenHash()).hasSize(64);
        assertThat(savedToken.getTokenHash()).isNotEqualTo(token);
        assertThat(savedToken.getUsedAt()).isNull();

        ResponseEntity<String> confirmResponse = restTemplate.postForEntity(
                "/api/auth/password-reset/confirm",
                Map.of(
                        "token", token,
                        "newPassword", "newpassword123"
                ),
                String.class
        );

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(passwordResetTokenRepository.findById(savedToken.getId()).orElseThrow().getUsedAt())
                .isNotNull();
        assertThat(userRepository.findByEmail("reset-confirm@example.com").orElseThrow().getPasswordChangedAt())
                .isNotNull();

        HttpHeaders preResetTokenHeaders = new HttpHeaders();
        preResetTokenHeaders.setBearerAuth(preResetAccessToken);
        ResponseEntity<String> oldTokenMeResponse = restTemplate.exchange(
                "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(preResetTokenHeaders),
                String.class
        );
        assertThat(oldTokenMeResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> oldPasswordLoginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of(
                        "email", "reset-confirm@example.com",
                        "password", "password123"
                ),
                String.class
        );
        assertThat(oldPasswordLoginResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> newPasswordLoginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of(
                        "email", "reset-confirm@example.com",
                        "password", "newpassword123"
                ),
                String.class
        );
        assertThat(newPasswordLoginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> reusedTokenResponse = restTemplate.postForEntity(
                "/api/auth/password-reset/confirm",
                Map.of(
                        "token", token,
                        "newPassword", "another123"
                ),
                String.class
        );
        assertThat(reusedTokenResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void passwordResetConfirmRejectsExpiredToken() {
        signup("reset-expired@example.com", "password123");
        User user = userRepository.findByEmail("reset-expired@example.com").orElseThrow();
        String token = "expired-token";
        passwordResetTokenRepository.save(PasswordResetToken.create(
                user,
                sha256(token),
                LocalDateTime.now().minusMinutes(1)
        ));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/password-reset/confirm",
                Map.of(
                        "token", token,
                        "newPassword", "newpassword123"
                ),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void passwordResetRequestIsRateLimited() {
        for (int index = 0; index < 5; index++) {
            assertThat(requestPasswordReset("rate-reset@example.com").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> limitedResponse = requestPasswordReset("rate-reset@example.com");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void passwordResetConfirmIsRateLimited() {
        for (int index = 0; index < 10; index++) {
            ResponseEntity<String> response = confirmPasswordReset("invalid-token-" + index, "newpassword123");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<String> limitedResponse = confirmPasswordReset("invalid-token-final", "newpassword123");

        assertThat(limitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private String findAccessTokenSetCookie(ResponseEntity<String> response) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull();
        return setCookies.stream()
                .filter(cookie -> cookie.startsWith(JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "="))
                .findFirst()
                .orElseThrow();
    }

    private void signup(String email, String password) {
        restTemplate.postForEntity(
                "/api/auth/signup",
                Map.of(
                        "email", email,
                        "password", password,
                        "agreedToTerms", true
                ),
                String.class
        );
    }

    private ResponseEntity<String> requestPasswordReset(String email) {
        return restTemplate.postForEntity(
                "/api/auth/password-reset/request",
                Map.of("email", email),
                String.class
        );
    }

    private ResponseEntity<String> confirmPasswordReset(String token, String newPassword) {
        return restTemplate.postForEntity(
                "/api/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", newPassword),
                String.class
        );
    }

    private String capturePasswordResetLink() {
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordResetEmailSender).sendPasswordResetLink(
                org.mockito.ArgumentMatchers.any(User.class),
                linkCaptor.capture()
        );
        return linkCaptor.getValue();
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
