package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
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
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "admin.emails=admin@example.com"
})
class AdminUserSuspensionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        adminAuditLogRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void adminCanSuspendAndReactivateUser() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);

        ResponseEntity<String> suspendResponse = restTemplate.exchange(
                "/api/admin/users/" + member.getId() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 정지"), adminHeaders),
                String.class
        );
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> blockedLoginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of("email", "member@example.com", "password", "password123"),
                String.class
        );
        assertThat(blockedLoginResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blockedLoginResponse.getBody()).contains("정지된 계정");

        ResponseEntity<String> activateResponse = restTemplate.exchange(
                "/api/admin/users/" + member.getId() + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders),
                String.class
        );
        assertThat(activateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> restoredLoginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                Map.of("email", "member@example.com", "password", "password123"),
                String.class
        );
        assertThat(restoredLoginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(adminAuditLogRepository.findAll()).hasSize(2);
    }

    @Test
    void adminCannotSuspendSelf() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users/" + admin.getId() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 정지"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void suspendedUsersExistingTokenIsRejectedImmediately() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        String memberToken = signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        HttpHeaders memberHeaders = new HttpHeaders();
        memberHeaders.setBearerAuth(memberToken);
        ResponseEntity<String> beforeSuspend = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(memberHeaders),
                String.class
        );
        assertThat(beforeSuspend.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        restTemplate.exchange(
                "/api/admin/users/" + member.getId() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 정지"), adminHeaders),
                String.class
        );

        ResponseEntity<String> afterSuspend = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(memberHeaders),
                String.class
        );
        assertThat(afterSuspend.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void suspendingNonexistentUserReturnsNotFound() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users/999999/suspend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "테스트 정지"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String extractAccessTokenFromCookie(ResponseEntity<String> loginResponse) {
        String setCookieHeader = loginResponse.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String prefix = JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME + "=";
        int start = setCookieHeader.indexOf(prefix) + prefix.length();
        int end = setCookieHeader.indexOf(';', start);
        return end == -1 ? setCookieHeader.substring(start) : setCookieHeader.substring(start, end);
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity("/api/auth/signup", request, String.class);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity("/api/auth/login", request, String.class);
        return extractAccessTokenFromCookie(loginResponse);
    }

    // 공개 signup/login은 더 이상 ADMIN_EMAILS만으로 ADMIN을 부여하지 않는다(2026-08-03
    // P0 수정). 테스트에서 관리자 토큰이 필요하면 가입 후 role을 직접 동기화한다 —
    // JwtAuthenticationFilter가 매 요청마다 DB에서 role을 새로 조회하므로, 이미 발급된
    // 토큰도 이 동기화 이후 즉시 관리자 권한으로 동작한다.
    private String signupAndLoginAsAdmin(String email) throws Exception {
        String token = signupAndLogin(email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.syncRole(UserRole.ADMIN);
        userRepository.save(user);
        return token;
    }
}
