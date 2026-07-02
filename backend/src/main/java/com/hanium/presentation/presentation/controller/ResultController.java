package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.result.ResultCommandService;
import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/results")
public class ResultController {

    // AnalysisController와 동일한 jobId 형식 검증 (경로 조작 방지 목적)
    private static final String JOB_ID_PATTERN = "^\\d{14}-[0-9a-f]{8}$";
    private static final String JOB_ID_MESSAGE = "jobId 형식이 올바르지 않습니다.";

    private final ResultQueryService resultQueryService;
    private final ResultCommandService resultCommandService;

    public ResultController(
            ResultQueryService resultQueryService,
            ResultCommandService resultCommandService
    ) {
        this.resultQueryService = resultQueryService;
        this.resultCommandService = resultCommandService;
    }

    @GetMapping
    public ApiResponse<List<ResultSummaryResponse>> getResults(
            Authentication authentication
    ) {
        List<ResultSummaryResponse> response = resultQueryService.getResultSummaries(
                getCurrentUserId(authentication)
        );

        return ApiResponse.success(
                "분석 결과 목록 조회가 완료되었습니다.",
                response
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
}
