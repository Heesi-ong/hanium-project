package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.config.UserRateLimiter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "security.jwt.secret=presentation-coaching-local-jwt-secret-change-me-2026"
})
class ResultVideoStreamingIntegrationTest {

    private static final String OWNER_JOB_ID = "20260707092000-aaaaaaaa";
    private static final String OTHER_JOB_ID = "20260707092001-bbbbbbbb";
    private static final String SECRET = "presentation-coaching-local-jwt-secret-change-me-2026";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UploadedVideoRepository uploadedVideoRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
    }

    @Test
    void ownerCanIssueTokenAndStreamVideoWithoutAuthorizationHeader() throws Exception {
        String ownerToken = signupAndLogin("video-owner@example.com");
        Long ownerId = userRepository.findByEmail("video-owner@example.com")
                .map(User::getId)
                .orElseThrow();
        createVideoFixture(OWNER_JOB_ID, ownerId, "0123456789");

        ResponseEntity<String> tokenResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID + "/video-access-token",
                HttpMethod.POST,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode tokenBody = objectMapper.readTree(tokenResponse.getBody());
        String accessToken = tokenBody.path("data").path("token").asText();
        assertThat(tokenBody.path("data").path("expiresInSeconds").asLong()).isEqualTo(300L);

        HttpHeaders rangeHeaders = new HttpHeaders();
        rangeHeaders.set(HttpHeaders.RANGE, "bytes=0-3");
        ResponseEntity<byte[]> streamResponse = restTemplate.exchange(
                createVideoUrl(OWNER_JOB_ID, accessToken),
                HttpMethod.GET,
                new HttpEntity<>(rangeHeaders),
                byte[].class
        );

        assertThat(streamResponse.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(streamResponse.getHeaders().getContentType().toString()).isEqualTo("video/mp4");
        assertThat(new String(streamResponse.getBody(), StandardCharsets.UTF_8)).isEqualTo("0123");
    }

    @Test
    void otherUserCannotIssueAccessTokenForOwnersJob() throws Exception {
        String otherToken = signupAndLogin("video-other@example.com");
        Long ownerId = userRepository.save(User.create(
                        "video-owner-without-login@example.com",
                        "encoded-password"
                ))
                .getId();
        createVideoFixture(OWNER_JOB_ID, ownerId, "0123456789");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID + "/video-access-token",
                HttpMethod.POST,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
    }

    @Test
    void tokenForDifferentJobCannotStreamOwnersVideo() throws Exception {
        String ownerToken = signupAndLogin("owner-token@example.com");
        String otherToken = signupAndLogin("other-token@example.com");
        Long ownerId = userRepository.findByEmail("owner-token@example.com")
                .map(User::getId)
                .orElseThrow();
        Long otherId = userRepository.findByEmail("other-token@example.com")
                .map(User::getId)
                .orElseThrow();
        createVideoFixture(OWNER_JOB_ID, ownerId, "owner-video");
        createVideoFixture(OTHER_JOB_ID, otherId, "other-video");

        String otherVideoToken = issueToken(OTHER_JOB_ID, otherToken);

        ResponseEntity<String> response = restTemplate.exchange(
                createVideoUrl(OWNER_JOB_ID, otherVideoToken),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class
        );

        assertThat(ownerToken).isNotBlank();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
    }

    @Test
    void expiredTokenCannotStreamVideo() throws Exception {
        Long ownerId = userRepository.save(User.create(
                        "expired-video-owner@example.com",
                        "encoded-password"
                ))
                .getId();
        createVideoFixture(OWNER_JOB_ID, ownerId, "0123456789");
        String expiredToken = createExpiredVideoToken(OWNER_JOB_ID, ownerId);

        ResponseEntity<String> response = restTemplate.exchange(
                createVideoUrl(OWNER_JOB_ID, expiredToken),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, String> request = Map.of(
                "email", email,
                "password", "password123"
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
        return loginBody.path("data").path("accessToken").asText();
    }

    private String issueToken(String jobId, String bearerToken) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/results/" + jobId + "/video-access-token",
                HttpMethod.POST,
                createAuthorizedEntity(bearerToken),
                String.class
        );

        JsonNode responseBody = objectMapper.readTree(response.getBody());
        return responseBody.path("data").path("token").asText();
    }

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private String createVideoUrl(String jobId, String accessToken) {
        return UriComponentsBuilder
                .fromPath("/api/results/{jobId}/video")
                .queryParam("access", accessToken)
                .buildAndExpand(jobId)
                .toUriString();
    }

    private void createVideoFixture(
            String jobId,
            Long ownerId,
            String content
    ) throws Exception {
        Path videoPath = tempDir.resolve(jobId + ".mp4");
        Files.writeString(videoPath, content);

        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                "original.mp4",
                videoPath.toString(),
                VideoFileType.MP4,
                Files.size(videoPath)
        ));
    }

    private String createExpiredVideoToken(String jobId, Long ownerId) {
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant issuedAt = Instant.now().minusSeconds(600);
        Instant expiresAt = Instant.now().minusSeconds(300);

        return Jwts.builder()
                .claim("jobId", jobId)
                .claim("ownerId", ownerId)
                .claim("purpose", "video-access")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }
}
