package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

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
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
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

        ResponseEntity<String> response = deleteWithReason(
                "/api/admin/results/" + job.getJobId(), headers, "테스트 삭제");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisJobRepository.findByJobId(job.getJobId())).isEmpty();
        assertThat(adminAuditLogRepository.findAll()).hasSize(1);
    }

    @Test
    void deletingNonexistentResultReturnsNotFound() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = deleteWithReason(
                "/api/admin/results/does-not-exist", headers, "테스트 삭제");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingQueuedJobIsNotAllowed() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        signupAndLogin("member@example.com");
        User member = userRepository.findByEmail("member@example.com").orElseThrow();

        AnalysisJob job = AnalysisJob.create("20260715090100-queued001", member.getId());
        job.enqueue(true, true);
        analysisJobRepository.save(job);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        ResponseEntity<String> response = deleteWithReason(
                "/api/admin/results/" + job.getJobId(), headers, "테스트 삭제");

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

        ResponseEntity<String> response = deleteWithReason(
                "/api/admin/results/" + job.getJobId(), headers, "테스트 삭제");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(analysisJobRepository.findByJobId(job.getJobId())).isPresent();
    }

    // TestRestTemplate의 기본 요청 팩토리(JdkClientHttpRequestFactory, Spring Boot 3.5
    // 기본값)는 java.net.http.HttpRequest.Builder#DELETE()의 무본문(no-body) 오버로드를
    // 그대로 사용하도록 하드코딩되어 있어, HttpEntity에 담은 본문이 DELETE 요청에서는
    // 조용히 버려진다(POST/PUT 등 다른 메서드는 영향 없음). 이 테스트 클래스에서만 HTTP
    // 요청 시점에 SimpleClientHttpRequestFactory(HttpURLConnection 기반, DELETE 본문 지원)
    // 로 일시적으로 교체해 우회한다.
    private ResponseEntity<String> deleteWithReason(String path, HttpHeaders headers, String reason) {
        RestTemplate underlying = restTemplate.getRestTemplate();
        ClientHttpRequestFactory originalFactory = underlying.getRequestFactory();
        underlying.setRequestFactory(new SimpleClientHttpRequestFactory());
        try {
            return restTemplate.exchange(
                    path,
                    HttpMethod.DELETE,
                    new HttpEntity<>(Map.of("reason", reason), headers),
                    String.class
            );
        } finally {
            underlying.setRequestFactory(originalFactory);
        }
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
