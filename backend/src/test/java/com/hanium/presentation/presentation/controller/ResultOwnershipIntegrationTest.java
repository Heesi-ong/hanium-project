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
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResultOwnershipIntegrationTest {

    private static final String OWNER_JOB_ID = "20260702180000-aaaaaaaa";
    private static final String OTHER_JOB_ID = "20260702180001-bbbbbbbb";

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
    private FilePathGenerator filePathGenerator;

    @Autowired
    private JsonFileStorage jsonFileStorage;

    @BeforeEach
    void setUp() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void resultApisOnlyAllowOwnerAccess() throws Exception {
        String ownerToken = signupAndLogin("owner@example.com");
        String otherToken = signupAndLogin("other@example.com");

        Long ownerId = userRepository.findByEmail("owner@example.com")
                .map(User::getId)
                .orElseThrow();
        Long otherId = userRepository.findByEmail("other@example.com")
                .map(User::getId)
                .orElseThrow();

        createResultFixture(OWNER_JOB_ID, ownerId, "owner-video.mp4");
        createResultFixture(OTHER_JOB_ID, otherId, "other-video.mp4");

        ResponseEntity<String> otherListResponse = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(otherListResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode otherListBody = objectMapper.readTree(otherListResponse.getBody());
        assertThat(otherListBody.path("data")).hasSize(1);
        assertThat(otherListBody.path("data").get(0).path("jobId").asText()).isEqualTo(OTHER_JOB_ID);

        ResponseEntity<String> deniedGetResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(deniedGetResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deniedGetResponse.getBody()).doesNotContain("owner-video.mp4");

        ResponseEntity<String> deniedDeleteResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.DELETE,
                createAuthorizedEntity(otherToken),
                String.class
        );

        assertThat(deniedDeleteResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(analysisJobRepository.existsByJobId(OWNER_JOB_ID)).isTrue();
        assertThat(uploadedVideoRepository.existsByJobId(OWNER_JOB_ID)).isTrue();
        assertThat(Files.exists(filePathGenerator.generateFinalResultPath(OWNER_JOB_ID))).isTrue();

        ResponseEntity<String> ownerGetResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.GET,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(ownerGetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerGetResponse.getBody()).contains(OWNER_JOB_ID);

        ResponseEntity<String> ownerDeleteResponse = restTemplate.exchange(
                "/api/results/" + OWNER_JOB_ID,
                HttpMethod.DELETE,
                createAuthorizedEntity(ownerToken),
                String.class
        );

        assertThat(ownerDeleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisJobRepository.existsByJobId(OWNER_JOB_ID)).isFalse();
        assertThat(uploadedVideoRepository.existsByJobId(OWNER_JOB_ID)).isFalse();
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

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private void createResultFixture(
            String jobId,
            Long ownerId,
            String originalFileName
    ) {
        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                originalFileName,
                Path.of("storage", "uploads", jobId, "original.mp4").toString(),
                VideoFileType.MP4,
                1024L
        ));
        jsonFileStorage.saveJson(
                filePathGenerator.generateFinalResultPath(jobId),
                Map.of(
                        "jobId", jobId,
                        "originalFileName", originalFileName,
                        "scoreSummary", Map.of("totalScore", 90),
                        "feedback", Map.of("overall", "owner scoped feedback")
                )
        );
    }
}
