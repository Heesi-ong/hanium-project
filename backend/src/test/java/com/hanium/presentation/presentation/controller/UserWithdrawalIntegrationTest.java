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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "storage.upload-path=${user.dir}/build/test-storage/user-withdrawal/uploads",
                "storage.result-path=${user.dir}/build/test-storage/user-withdrawal/results"
        }
)
class UserWithdrawalIntegrationTest {

    private static final String FIRST_JOB_ID = "20260703190000-aaaaaaaa";
    private static final String SECOND_JOB_ID = "20260703190001-bbbbbbbb";
    private static final Path TEST_STORAGE_ROOT = Path.of("build", "test-storage", "user-withdrawal");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MockMvc mockMvc;

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

    @BeforeEach
    void setUp() throws IOException {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        userRepository.deleteAll();
        deleteRecursively(TEST_STORAGE_ROOT);
    }

    @Test
    void withdrawDeletesUserOwnedJobsAndFiles() throws Exception {
        String token = signupAndLogin("withdraw@example.com", "password123");
        Long userId = userRepository.findByEmail("withdraw@example.com")
                .map(User::getId)
                .orElseThrow();

        createResultFixture(FIRST_JOB_ID, userId);
        createResultFixture(SECOND_JOB_ID, userId);

        mockMvc.perform(delete("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "password123"))))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(analysisJobRepository.existsByJobId(FIRST_JOB_ID)).isFalse();
        assertThat(analysisJobRepository.existsByJobId(SECOND_JOB_ID)).isFalse();
        assertThat(uploadedVideoRepository.existsByJobId(FIRST_JOB_ID)).isFalse();
        assertThat(uploadedVideoRepository.existsByJobId(SECOND_JOB_ID)).isFalse();
        assertThat(Files.exists(filePathGenerator.generateUploadDirectory(FIRST_JOB_ID))).isFalse();
        assertThat(Files.exists(filePathGenerator.generateResultDirectory(FIRST_JOB_ID))).isFalse();
        assertThat(Files.exists(filePathGenerator.generateUploadDirectory(SECOND_JOB_ID))).isFalse();
        assertThat(Files.exists(filePathGenerator.generateResultDirectory(SECOND_JOB_ID))).isFalse();

        ResponseEntity<String> reusedTokenResponse = restTemplate.exchange(
                "/api/results",
                HttpMethod.GET,
                createAuthorizedEntity(token),
                String.class
        );
        assertThat(reusedTokenResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void withdrawRollsBackAllDeletionsWhenOneJobCannotBeDeleted() throws Exception {
        String token = signupAndLogin("partial-failure@example.com", "password123");
        Long userId = userRepository.findByEmail("partial-failure@example.com")
                .map(User::getId)
                .orElseThrow();

        createResultFixture(FIRST_JOB_ID, userId);
        AnalysisJob queuedJob = AnalysisJob.create(SECOND_JOB_ID, userId);
        queuedJob.enqueue(false, false);
        analysisJobRepository.save(queuedJob);

        mockMvc.perform(delete("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "password123"))))
                .andExpect(status().isInternalServerError());

        // 대기 중인 SECOND_JOB_ID는 삭제할 수 없어 전체 탈퇴가 실패했으므로, 이미
        // 정상적으로 삭제 가능했던 FIRST_JOB_ID의 DB 행/파일도 함께 롤백돼야 합니다.
        // 원자성이 깨지면 계정은 남았는데 FIRST_JOB_ID만 먼저 사라지는 반쪽 상태가 됩니다.
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(analysisJobRepository.existsByJobId(FIRST_JOB_ID)).isTrue();
        assertThat(analysisJobRepository.existsByJobId(SECOND_JOB_ID)).isTrue();
        assertThat(uploadedVideoRepository.existsByJobId(FIRST_JOB_ID)).isTrue();

        // 파일 삭제는 트랜잭션 커밋 이후로 미뤄지므로, 트랜잭션 자체가 롤백되면
        // FIRST_JOB_ID의 실제 영상/결과 파일도 지워지지 않고 그대로 남아야 합니다.
        // DB 행만 살아남고 파일은 이미 사라진 상태(고스트 job)가 되면 안 됩니다.
        assertThat(Files.exists(filePathGenerator.generateUploadDirectory(FIRST_JOB_ID))).isTrue();
        assertThat(Files.exists(filePathGenerator.generateResultDirectory(FIRST_JOB_ID))).isTrue();
    }

    @Test
    void wrongPasswordDoesNotDeleteAnything() throws Exception {
        String token = signupAndLogin("wrong-password@example.com", "password123");
        Long userId = userRepository.findByEmail("wrong-password@example.com")
                .map(User::getId)
                .orElseThrow();

        createResultFixture(FIRST_JOB_ID, userId);

        mockMvc.perform(delete("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(analysisJobRepository.existsByJobId(FIRST_JOB_ID)).isTrue();
        assertThat(uploadedVideoRepository.existsByJobId(FIRST_JOB_ID)).isTrue();
        assertThat(Files.exists(filePathGenerator.generateUploadDirectory(FIRST_JOB_ID))).isTrue();
        assertThat(Files.exists(filePathGenerator.generateResultDirectory(FIRST_JOB_ID))).isTrue();
    }

    @Test
    void withdrawRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/me",
                HttpMethod.DELETE,
                new HttpEntity<>(Map.of("password", "password123")),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String signupAndLogin(String email, String password) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", password,
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
        return loginBody.path("data").path("accessToken").asText();
    }

    private HttpEntity<Void> createAuthorizedEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private void createResultFixture(String jobId, Long ownerId) throws IOException {
        Path uploadDirectory = filePathGenerator.generateUploadDirectory(jobId);
        Path resultDirectory = filePathGenerator.generateResultDirectory(jobId);
        Files.createDirectories(uploadDirectory);
        Files.createDirectories(resultDirectory);

        Path originalVideoPath = uploadDirectory.resolve("original.mp4");
        Files.writeString(originalVideoPath, "fake mp4 content");
        Files.writeString(resultDirectory.resolve("final-result.json"), "{}");

        analysisJobRepository.save(AnalysisJob.create(jobId, ownerId));
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                jobId + ".mp4",
                originalVideoPath.toString(),
                VideoFileType.MP4,
                Files.size(originalVideoPath)
        ));
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("테스트 파일 삭제 실패: " + path, exception);
                        }
                    });
        }
    }
}
