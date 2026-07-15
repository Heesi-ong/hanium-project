package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
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
class AdminResultDeletionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UploadedVideoRepository uploadedVideoRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        adminAuditLogRepository.deleteAll();
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void adminCanDeleteAnyUsersResult() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob job = AnalysisJob.create("20260715090000-abcd1234", member.getId());
        job.complete();
        analysisJobRepository.save(job);
        uploadedVideoRepository.save(UploadedVideo.create(
                job.getJobId(),
                "presentation.mp4",
                "/tmp/presentation.mp4",
                VideoFileType.MP4,
                1024L
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/results/" + job.getJobId(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisJobRepository.findByJobId(job.getJobId())).isEmpty();
        assertThat(adminAuditLogRepository.findAll()).hasSize(1);
    }

    @Test
    void deletingNonexistentResultReturnsNotFound() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/results/does-not-exist",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingQueuedJobIsNotAllowed() throws Exception {
        String adminToken = signupAndLogin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob job = AnalysisJob.create("20260715090100-queued001", member.getId());
        job.enqueue(true, true);
        analysisJobRepository.save(job);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/results/" + job.getJobId(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(analysisJobRepository.findByJobId(job.getJobId())).isPresent();
    }

    @Test
    void nonAdminCannotDeleteResult() throws Exception {
        String memberToken = signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob job = AnalysisJob.create("20260715090200-member001", member.getId());
        job.complete();
        analysisJobRepository.save(job);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(memberToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/admin/results/" + job.getJobId(),
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(analysisJobRepository.findByJobId(job.getJobId())).isPresent();
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
