package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.result.AnalysisFrameOverlayStorage;
import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.application.result.VideoStreamingService;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.PagedResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import com.hanium.presentation.presentation.dto.response.VideoAccessTokenResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.CacheControl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/results")
public class ResultController {

    // AnalysisController와 동일한 jobId 형식 검증 (경로 조작 방지 목적)
    private static final String JOB_ID_PATTERN = "^\\d{14}-[0-9a-f]{8}$";
    private static final String JOB_ID_MESSAGE = "jobId 형식이 올바르지 않습니다.";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final long VIDEO_ACCESS_TOKEN_EXPIRES_IN_SECONDS = 300L;

    private final ResultQueryService resultQueryService;
    private final ResultCommandService resultCommandService;
    private final VideoStreamingService videoStreamingService;
    private final AnalysisFrameOverlayStorage analysisFrameOverlayStorage;

    public ResultController(
            ResultQueryService resultQueryService,
            ResultCommandService resultCommandService,
            VideoStreamingService videoStreamingService,
            AnalysisFrameOverlayStorage analysisFrameOverlayStorage
    ) {
        this.resultQueryService = resultQueryService;
        this.resultCommandService = resultCommandService;
        this.videoStreamingService = videoStreamingService;
        this.analysisFrameOverlayStorage = analysisFrameOverlayStorage;
    }

    @GetMapping
    public ApiResponse<PagedResponse<ResultSummaryResponse>> getResults(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(
                getCurrentUserId(authentication),
                createPageable(page, size)
        );

        return ApiResponse.success(
                "분석 결과 목록 조회가 완료되었습니다.",
                PagedResponse.from(response)
        );
    }

    @GetMapping("/{jobId}")
    public ApiResponse<AnalysisResultResponse> getResult(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        AnalysisResultResponse response = resultQueryService.getFinalResult(
                jobId,
                getCurrentUserId(authentication)
        );

        return ApiResponse.success(
                "분석 결과 조회가 완료되었습니다.",
                response
        );
    }

    @GetMapping("/{jobId}/frames/{fileName}")
    public ResponseEntity<byte[]> getOverlayFrame(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            @PathVariable
            @Pattern(regexp = "^frame_\\d{3}\\.jpg$", message = "프레임 파일명이 올바르지 않습니다.")
            String fileName,
            Authentication authentication
    ) {
        resultQueryService.assertResultOwnership(jobId, getCurrentUserId(authentication));
        byte[] image = analysisFrameOverlayStorage.readFrame(jobId, fileName);

        // 소유자 전용이면서 재분석 시 같은 파일명으로 덮어써지는 가변 리소스입니다.
        // 브라우저 private 캐시는 인증 쿠키로 분리되지 않으므로, 캐시된 이전 실행 프레임이
        // 계정 전환 후에도 보이거나 소유권/삭제 재검사를 우회하지 않도록 저장하지 않습니다.
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .body(image);
    }

    @PatchMapping("/{jobId}/memo")
    public ApiResponse<Void> updateMemo(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication,
            @Valid @RequestBody UpdateMemoRequest request
    ) {
        resultCommandService.updateMemo(
                jobId,
                getCurrentUserId(authentication),
                request.memo()
        );

        return ApiResponse.success("메모가 저장되었습니다.");
    }

    @DeleteMapping("/{jobId}")
    public ApiResponse<Void> deleteResult(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        resultCommandService.deleteResult(
                jobId,
                getCurrentUserId(authentication)
        );

        return ApiResponse.success("분석 결과가 삭제되었습니다.");
    }

    @PostMapping("/{jobId}/video-access-token")
    public ApiResponse<VideoAccessTokenResponse> issueVideoAccessToken(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            Authentication authentication
    ) {
        String token = videoStreamingService.issueAccessToken(
                jobId,
                getCurrentUserId(authentication)
        );

        return ApiResponse.success(
                "영상 재생 토큰이 발급되었습니다.",
                new VideoAccessTokenResponse(token, VIDEO_ACCESS_TOKEN_EXPIRES_IN_SECONDS)
        );
    }

    @GetMapping("/{jobId}/video")
    public ResponseEntity<ResourceRegion> streamVideo(
            @PathVariable @Pattern(regexp = JOB_ID_PATTERN, message = JOB_ID_MESSAGE) String jobId,
            @RequestParam("access") String accessToken,
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        UploadedVideo uploadedVideo = videoStreamingService.resolveVideoForStreaming(
                jobId,
                accessToken
        );

        // MinIO에 이미 미러링된 영상이면 브라우저를 presigned URL로 바로 리다이렉트해
        // 백엔드가 바이트 스트리밍을 직접 처리하지 않도록 합니다. 아직 미러링되지 않았거나
        // MinIO가 응답하지 않으면 null이 반환되어 아래 기존 로컬 디스크 스트리밍으로 이어집니다.
        String presignedStreamingUrl = videoStreamingService.resolvePresignedStreamingUrl(jobId, uploadedVideo);

        if (presignedStreamingUrl != null) {
            return createRedirectResponse(presignedStreamingUrl);
        }

        return createLocalStreamingResponse(uploadedVideo, headers);
    }

    private ResponseEntity<ResourceRegion> createRedirectResponse(String presignedStreamingUrl) {
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(presignedStreamingUrl))
                .<ResourceRegion>build();
    }

    private Long getCurrentUserId(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof Long userId) {
            return userId;
        }

        if (details instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException("인증 정보에서 사용자 id를 찾을 수 없습니다.");
    }

    private Pageable createPageable(int page, int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = size <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(size, MAX_PAGE_SIZE);

        return PageRequest.of(normalizedPage, normalizedSize);
    }

    private ResourceRegion createResourceRegion(
            FileSystemResource resource,
            HttpHeaders headers
    ) throws IOException {
        List<HttpRange> ranges = headers.getRange();
        if (!ranges.isEmpty()) {
            return ranges.get(0).toResourceRegion(resource);
        }

        long contentLength = resource.contentLength();
        return new ResourceRegion(resource, 0, contentLength);
    }

    private ResponseEntity<ResourceRegion> createLocalStreamingResponse(
            UploadedVideo uploadedVideo,
            HttpHeaders headers
    ) throws IOException {
        FileSystemResource resource = new FileSystemResource(uploadedVideo.getStoredFilePath());
        ResourceRegion resourceRegion = createResourceRegion(resource, headers);

        // 영상 바이너리는 JSON ApiResponse로 감싸지 않고 HTTP Range 스트리밍 응답으로 직접 반환합니다.
        return ResponseEntity
                .status(HttpStatus.PARTIAL_CONTENT)
                .contentType(resolveMediaType(uploadedVideo.getFileType()))
                .body(resourceRegion);
    }

    private MediaType resolveMediaType(VideoFileType fileType) {
        return switch (fileType) {
            case MP4 -> MediaType.valueOf("video/mp4");
            case MOV -> MediaType.valueOf("video/quicktime");
            case AVI -> MediaType.valueOf("video/x-msvideo");
            case MKV -> MediaType.valueOf("video/x-matroska");
        };
    }

    public record UpdateMemoRequest(
            @Size(max = 200, message = "메모는 200자를 초과할 수 없습니다.")
            String memo
    ) {
    }
}
