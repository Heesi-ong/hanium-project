package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "admin.emails=admin@example.com"
})
class AdminUserForceWithdrawalIntegrationTest {

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
    void adminCanForceWithdrawUser() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        String memberToken = signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        HttpHeaders memberHeaders = new HttpHeaders();
        memberHeaders.setBearerAuth(memberToken);
        ResponseEntity<String> beforeWithdraw = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(memberHeaders),
                String.class
        );
        assertThat(beforeWithdraw.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(adminToken);
        ResponseEntity<String> withdrawResponse = restTemplate.exchange(
                "/api/admin/users/" + member.getId() + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders),
                String.class
        );
        assertThat(withdrawResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(userRepository.findByEmail("member@example.com")).isEmpty();
        assertThat(adminAuditLogRepository.findAll()).hasSize(1);

        ResponseEntity<String> afterWithdraw = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                new HttpEntity<>(memberHeaders),
                String.class
        );
        assertThat(afterWithdraw.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminCannotForceWithdrawSelf() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        User admin = userRepository.findByEmail("admin@example.com").orElseThrow();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users/" + admin.getId() + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void forceWithdrawingNonexistentUserReturnsNotFound() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users/999999/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonAdminCannotForceWithdrawUser() throws Exception {
        String memberToken = signupAndLogin("member@example.com");
        signupAndLogin("target@example.com");
        User target = userRepository.findByEmail("target@example.com").orElseThrow();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users/" + target.getId() + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userRepository.findByEmail("target@example.com")).isPresent();
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );

        restTemplate.postForEntity("/api/auth/signup", request, String.class);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity("/api/auth/login", request, String.class);
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        return loginBody.path("data").path("accessToken").asText();
    }
}
