package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.result.AnalysisFrameOverlayStorage;
import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.application.result.VideoStreamingService;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.PagedResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import com.hanium.presentation.presentation.dto.response.VideoAccessTokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResultControllerTest {

    private static final String JOB_ID = "20260903120000-abcdef01";
    private static final long OWNER_ID = 11L;

    private ResultQueryService resultQueryService;
    private ResultCommandService resultCommandService;
    private VideoStreamingService videoStreamingService;
    private AnalysisFrameOverlayStorage analysisFrameOverlayStorage;
    private ResultController controller;

    @BeforeEach
    void setUp() {
        resultQueryService = mock(ResultQueryService.class);
        resultCommandService = mock(ResultCommandService.class);
        videoStreamingService = mock(VideoStreamingService.class);
        analysisFrameOverlayStorage = mock(AnalysisFrameOverlayStorage.class);
        controller = new ResultController(
                resultQueryService,
                resultCommandService,
                videoStreamingService,
                analysisFrameOverlayStorage
        );
    }

    private Authentication auth(Object details) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getDetails()).thenReturn(details);
        return authentication;
    }

    // --- createPageable 정규화 분기 ---

    @ParameterizedTest
    @CsvSource({
            "-5, 50, 0, 50",
            "0, 0, 0, 50",
            "2, 999, 2, 100",
            "1, 30, 1, 30",
    })
    void normalizesPageAndSizeBeforeQuerying(
            int page, int size, int expectedPage, int expectedSize
    ) {
        when(resultQueryService.getResultSummaries(eq(OWNER_ID), any(Pageable.class)))
                .thenReturn(Page.empty());

        controller.getResults(auth(OWNER_ID), page, size);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(resultQueryService).getResultSummaries(eq(OWNER_ID), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(expectedPage);
        assertThat(captor.getValue().getPageSize()).isEqualTo(expectedSize);
    }

    @Test
    void getResultsWrapsPageInPagedResponse() {
        Page<ResultSummaryResponse> page = Page.empty();
        when(resultQueryService.getResultSummaries(eq(OWNER_ID), any(Pageable.class))).thenReturn(page);

        ApiResponse<PagedResponse<ResultSummaryResponse>> response =
                controller.getResults(auth(OWNER_ID), 0, 50);

        assertThat(response.data()).isNotNull();
    }

    // --- getOverlayFrame: 소유권 검사 + no-store ---

    @Test
    void overlayFrameChecksOwnershipAndIsNotStored() {
        when(analysisFrameOverlayStorage.readFrame(JOB_ID, "frame_001.jpg"))
                .thenReturn(new byte[] {1, 2, 3});

        ResponseEntity<byte[]> response =
                controller.getOverlayFrame(JOB_ID, "frame_001.jpg", auth(OWNER_ID));

        verify(resultQueryService).assertResultOwnership(JOB_ID, OWNER_ID);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    // --- memo / delete / token 위임 ---

    @Test
    void updateMemoDelegates() {
        ApiResponse<Void> response = controller.updateMemo(
                JOB_ID, auth(OWNER_ID), new ResultController.UpdateMemoRequest("제목"));

        assertThat(response.success()).isTrue();
        verify(resultCommandService).updateMemo(JOB_ID, OWNER_ID, "제목");
    }

    @Test
    void deleteResultDelegates() {
        controller.deleteResult(JOB_ID, auth(OWNER_ID));
        verify(resultCommandService).deleteResult(JOB_ID, OWNER_ID);
    }

    @Test
    void issueVideoAccessTokenReturnsTokenWithFiveMinuteExpiry() {
        when(videoStreamingService.issueAccessToken(JOB_ID, OWNER_ID)).thenReturn("tok-123");

        ApiResponse<VideoAccessTokenResponse> response =
                controller.issueVideoAccessToken(JOB_ID, auth(OWNER_ID));

        assertThat(response.data().token()).isEqualTo("tok-123");
        assertThat(response.data().expiresInSeconds()).isEqualTo(300L);
    }

    // --- streamVideo: presigned 리다이렉트 vs 로컬 스트리밍 ---

    @Test
    void streamVideoRedirectsToPresignedUrlWhenMirrored() throws IOException {
        UploadedVideo video = UploadedVideo.create(JOB_ID, "v.mp4", "/tmp/v.mp4", VideoFileType.MP4, 10L);
        when(videoStreamingService.resolveVideoForStreaming(JOB_ID, "acc")).thenReturn(video);
        when(videoStreamingService.resolvePresignedStreamingUrl(JOB_ID, video))
                .thenReturn("https://minio.example/v.mp4?sig=x");

        ResponseEntity<ResourceRegion> response =
                controller.streamVideo(JOB_ID, "acc", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://minio.example/v.mp4?sig=x");
    }

    @ParameterizedTest
    @EnumSource(VideoFileType.class)
    void streamVideoServesLocalRangeResponseWithFileTypeMediaType(
            VideoFileType fileType,
            @TempDir Path tempDir
    ) throws IOException {
        Path file = tempDir.resolve("v" + fileType.getExtension());
        Files.write(file, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});
        UploadedVideo video = UploadedVideo.create(
                JOB_ID, "v" + fileType.getExtension(), file.toString(), fileType, 8L);
        when(videoStreamingService.resolveVideoForStreaming(JOB_ID, "acc")).thenReturn(video);
        when(videoStreamingService.resolvePresignedStreamingUrl(JOB_ID, video)).thenReturn(null);

        HttpHeaders headers = new HttpHeaders();
        headers.setRange(java.util.List.of(org.springframework.http.HttpRange.createByteRange(0, 3)));

        ResponseEntity<ResourceRegion> response =
                controller.streamVideo(JOB_ID, "acc", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("video/");
        assertThat(response.getBody().getCount()).isEqualTo(4L);
    }

    @Test
    void streamVideoWithoutRangeHeaderServesWholeResource(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("v.mp4");
        Files.write(file, new byte[] {0, 1, 2, 3, 4});
        UploadedVideo video = UploadedVideo.create(JOB_ID, "v.mp4", file.toString(), VideoFileType.MP4, 5L);
        when(videoStreamingService.resolveVideoForStreaming(JOB_ID, "acc")).thenReturn(video);
        when(videoStreamingService.resolvePresignedStreamingUrl(JOB_ID, video)).thenReturn(null);

        ResponseEntity<ResourceRegion> response =
                controller.streamVideo(JOB_ID, "acc", new HttpHeaders());

        assertThat(response.getBody().getPosition()).isZero();
        assertThat(response.getBody().getCount()).isEqualTo(5L);
    }

    // --- getCurrentUserId 분기 ---

    @Test
    void acceptsNumberAuthenticationDetails() {
        when(resultQueryService.getResultSummaries(eq(4L), any(Pageable.class))).thenReturn(Page.empty());

        controller.getResults(auth(Integer.valueOf(4)), 0, 50);

        verify(resultQueryService).getResultSummaries(eq(4L), any(Pageable.class));
    }

    @Test
    void rejectsAuthenticationDetailsWithoutUserId() {
        assertThatThrownBy(() -> controller.getResult(JOB_ID, auth("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용자 id");
    }
}
