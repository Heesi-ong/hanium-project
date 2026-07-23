package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.repository.StorageDeletionTaskRepository;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "analysis.dispatch.local-on-run=false",
        "analysis.worker.enabled=false",
        "storage.upload-path=${user.dir}/build/test-storage/video-llm-reanalysis/uploads",
        "storage.result-path=${user.dir}/build/test-storage/video-llm-reanalysis/results"
})
class VideoLlmReanalysisIntegrationTest {

    private static final String SOURCE_JOB_ID = "20260723130000-aaaaaaaa";
    private static final String FIRST_KEY = "video-reanalysis-key-0001";
    private static final String SECOND_KEY = "video-reanalysis-key-0002";

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
    private StorageDeletionTaskRepository storageDeletionTaskRepository;

    @MockBean
    private UserRateLimiter userRateLimiter;

    @BeforeEach
    void setUp() throws Exception {
        storageDeletionTaskRepository.deleteAll();
        analysisJobRepository.deleteAll();
        uploadedVideoRepository.deleteAll();
        userRepository.deleteAll();
        when(userRateLimiter.tryConsume(anyString(), any(String.class))).thenReturn(true);
        when(userRateLimiter.tryConsume(anyString(), any(Long.class))).thenReturn(true);
        when(userRateLimiter.wouldAllow(anyString(), any(String.class))).thenReturn(true);
        when(userRateLimiter.wouldAllow(anyString(), any(Long.class))).thenReturn(true);
    }

    @Test
    void acceptsOnceAndReusesSameChildForIdempotentReplay() throws Exception {
        String ownerToken = signupAndLogin("reanalysis-owner@example.com");
        String otherToken = signupAndLogin("reanalysis-other@example.com");
        Long ownerId = userRepository.findByEmail("reanalysis-owner@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedFallbackSource(ownerId);

        ResponseEntity<String> first = request(ownerToken, FIRST_KEY);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode firstBody = objectMapper.readTree(first.getBody());
        assertThat(firstBody.path("data").path("sourceJobId").asText()).isEqualTo(SOURCE_JOB_ID);
        assertThat(firstBody.path("data").path("status").asText()).isEqualTo("QUEUED");
        assertThat(firstBody.path("data").path("reused").asBoolean()).isFalse();
        String childJobId = firstBody.path("data").path("reanalysisJobId").asText();

        AnalysisJob child = analysisJobRepository.findByJobId(childJobId).orElseThrow();
        assertThat(child.getOwnerId()).isEqualTo(ownerId);
        assertThat(child.getAnalysisKind()).isEqualTo(AnalysisKind.VIDEO_LLM_REANALYSIS);
        assertThat(child.getSourceJobId()).isEqualTo(SOURCE_JOB_ID);
        assertThat(child.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(child.isUseVideoLlm()).isTrue();

        ResponseEntity<String> replay = request(ownerToken, FIRST_KEY);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode replayBody = objectMapper.readTree(replay.getBody());
        assertThat(replayBody.path("data").path("reused").asBoolean()).isTrue();
        assertThat(replayBody.path("data").path("reanalysisJobId").asText()).isEqualTo(childJobId);
        assertThat(findReanalysisChildren()).hasSize(1);

        ResponseEntity<String> activeDuplicate = request(ownerToken, SECOND_KEY);
        assertThat(activeDuplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(activeDuplicate.getBody()).contains("VIDEO_LLM_REANALYSIS_ALREADY_ACTIVE");

        ResponseEntity<String> forbidden = request(otherToken, "video-reanalysis-key-other");
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).contains("ANALYSIS_JOB_ACCESS_DENIED");

        ResponseEntity<String> missingHeader = request(ownerToken, null);
        assertThat(missingHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingHeader.getBody()).contains("INVALID_INPUT_VALUE");
    }

    @Test
    void preservesLineageAndSharedVideoUntilChildIsDeletedFirst() throws Exception {
        String ownerToken = signupAndLogin("reanalysis-delete-owner@example.com");
        Long ownerId = userRepository.findByEmail("reanalysis-delete-owner@example.com")
                .map(User::getId)
                .orElseThrow();
        createCompletedFallbackSource(ownerId);

        ResponseEntity<String> accepted = request(ownerToken, FIRST_KEY);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String childJobId = objectMapper.readTree(accepted.getBody())
                .path("data")
                .path("reanalysisJobId")
                .asText();
        AnalysisJob child = analysisJobRepository.findByJobId(childJobId).orElseThrow();
        child.complete();
        analysisJobRepository.saveAndFlush(child);

        ResponseEntity<String> sourceFirst = deleteResult(ownerToken, SOURCE_JOB_ID);

        assertThat(sourceFirst.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sourceFirst.getBody()).contains("ANALYSIS_DELETE_NOT_ALLOWED");
        assertThat(sourceFirst.getBody()).contains("재분석 결과를 먼저 삭제");
        assertThat(analysisJobRepository.findByJobId(SOURCE_JOB_ID)).isPresent();
        assertThat(analysisJobRepository.findByJobId(childJobId))
                .get()
                .extracting(AnalysisJob::getSourceJobId)
                .isEqualTo(SOURCE_JOB_ID);
        assertThat(uploadedVideoRepository.findByJobId(SOURCE_JOB_ID)).isPresent();
        assertThat(storageDeletionTaskRepository.count()).isZero();

        ResponseEntity<String> childDelete = deleteResult(ownerToken, childJobId);

        assertThat(childDelete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisJobRepository.findByJobId(childJobId)).isEmpty();
        assertThat(analysisJobRepository.findByJobId(SOURCE_JOB_ID)).isPresent();
        assertThat(uploadedVideoRepository.findByJobId(SOURCE_JOB_ID)).isPresent();
        assertThat(storageDeletionTaskRepository.findAll())
                .extracting(StorageDeletionTask::getObjectKeyPrefix)
                .containsExactly("results/" + childJobId + "/");

        ResponseEntity<String> sourceDelete = deleteResult(ownerToken, SOURCE_JOB_ID);

        assertThat(sourceDelete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysisJobRepository.findByJobId(SOURCE_JOB_ID)).isEmpty();
        assertThat(uploadedVideoRepository.findByJobId(SOURCE_JOB_ID)).isEmpty();
        assertThat(storageDeletionTaskRepository.findAll())
                .extracting(StorageDeletionTask::getObjectKeyPrefix)
                .containsExactlyInAnyOrder(
                        "results/" + childJobId + "/",
                        "results/" + SOURCE_JOB_ID + "/",
                        "uploads/" + SOURCE_JOB_ID + "/"
                );
    }

    private ResponseEntity<String> request(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(
                "/api/analysis/" + SOURCE_JOB_ID + "/video-llm-reanalysis",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("useOpenAi", false), headers),
                String.class
        );
    }

    private ResponseEntity<String> deleteResult(String token, String jobId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/results/" + jobId,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );
        restTemplate.postForEntity("/api/auth/signup", request, String.class);
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                request,
                String.class
        );
        return objectMapper.readTree(loginResponse.getBody())
                .path("data")
                .path("accessToken")
                .asText();
    }

    private void createCompletedFallbackSource(Long ownerId) throws Exception {
        Path videoPath = Path.of(
                System.getProperty("user.dir"),
                "build/test-storage/video-llm-reanalysis/uploads",
                SOURCE_JOB_ID,
                "original.mp4"
        );
        Files.createDirectories(videoPath.getParent());
        Files.writeString(videoPath, "fake mp4 content");

        UploadedVideo videoAsset = uploadedVideoRepository.saveAndFlush(UploadedVideo.create(
                SOURCE_JOB_ID,
                "original.mp4",
                videoPath.toString(),
                VideoFileType.MP4,
                Files.size(videoPath)
        ));
        AnalysisJob sourceJob = AnalysisJob.create(SOURCE_JOB_ID, ownerId);
        sourceJob.linkVideoAsset(videoAsset.getId());
        sourceJob.startBasicAnalysis();
        sourceJob.complete();
        sourceJob.recordVideoLlmGenerationMode(VideoLlmGenerationMode.FALLBACK);
        analysisJobRepository.saveAndFlush(sourceJob);
    }

    private List<AnalysisJob> findReanalysisChildren() {
        return analysisJobRepository.findAll().stream()
                .filter(job -> job.getAnalysisKind() == AnalysisKind.VIDEO_LLM_REANALYSIS)
                .toList();
    }
}
