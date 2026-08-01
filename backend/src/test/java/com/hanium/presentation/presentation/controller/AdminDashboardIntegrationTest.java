package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
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
class AdminDashboardIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void adminCanListUsersWithAnalysisJobCountsAndStats() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        signupAndLogin("member@example.com");

        User member = userRepository.findByEmail("member@example.com").orElseThrow();
        analysisJobRepository.save(AnalysisJob.create("job-1", member.getId()));
        analysisJobRepository.save(AnalysisJob.create("job-2", member.getId()));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> usersResponse = restTemplate.exchange(
                "/api/admin/users?page=0&size=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(usersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode usersBody = objectMapper.readTree(usersResponse.getBody());
        JsonNode content = usersBody.path("data").path("content");
        assertThat(content).hasSize(2);
        assertThat(usersBody.path("data").path("totalElements").asLong()).isEqualTo(2L);

        JsonNode memberNode = findByEmail(content, "member@example.com");
        assertThat(memberNode.path("analysisJobCount").asLong()).isEqualTo(2L);
        assertThat(memberNode.path("role").asText()).isEqualTo("USER");

        JsonNode adminNode = findByEmail(content, "admin@example.com");
        assertThat(adminNode.path("role").asText()).isEqualTo("ADMIN");
        // 배치 집계(countByOwnerIdIn)는 작업이 없는 사용자는 GROUP BY 결과에 행 자체가
        // 없으므로, 0으로 채워지는 fallback 경로를 명시적으로 검증합니다.
        assertThat(adminNode.path("analysisJobCount").asLong()).isEqualTo(0L);

        ResponseEntity<String> statsResponse = restTemplate.exchange(
                "/api/admin/stats",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(statsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode statsBody = objectMapper.readTree(statsResponse.getBody()).path("data");
        assertThat(statsBody.path("totalUsers").asLong()).isEqualTo(2L);
        assertThat(statsBody.path("adminUsers").asLong()).isEqualTo(1L);
        assertThat(statsBody.path("totalAnalysisJobs").asLong()).isEqualTo(2L);
    }

    @Test
    void adminCanSearchUsersByEmailStatusAndRole() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        signupAndLogin("target.member@example.com");
        signupAndLogin("other@example.com");

        User target = userRepository.findByEmail("target.member@example.com").orElseThrow();
        target.suspend();
        userRepository.save(target);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/users?email=TARGET.MEMBER&status=SUSPENDED&role=USER&page=0&size=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(data.path("content")).hasSize(1);
        assertThat(data.path("content").get(0).path("email").asText())
                .isEqualTo("target.member@example.com");
        assertThat(data.path("content").get(0).path("status").asText())
                .isEqualTo("SUSPENDED");
    }

    private JsonNode findByEmail(JsonNode content, String email) {
        for (JsonNode node : content) {
            if (email.equals(node.path("email").asText())) {
                return node;
            }
        }
        throw new AssertionError("사용자를 찾지 못했습니다: " + email);
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
