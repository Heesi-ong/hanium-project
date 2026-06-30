package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.analysis.AnalysisCommandService;
import com.hanium.presentation.application.analysis.AnalysisQueryService;
import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.request.AnalysisRunRequest;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisUploadResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisCommandService analysisCommandService;
    private final AnalysisQueryService analysisQueryService;
    private final ResultQueryService resultQueryService;

    public AnalysisController(
            AnalysisCommandService analysisCommandService,
            AnalysisQueryService analysisQueryService,
            ResultQueryService resultQueryService
    ) {
        this.analysisCommandService = analysisCommandService;
        this.analysisQueryService = analysisQueryService;
        this.resultQueryService = resultQueryService;
    }

    @PostMapping("/upload")
    public ApiResponse<AnalysisUploadResponse> uploadVideo(
            @RequestPart("file") MultipartFile file
    ) {
        AnalysisUploadResponse response = analysisCommandService.uploadVideo(file);

        return ApiResponse.success(
                "영상 업로드가 완료되었습니다.",
                response
        );
    }

    @GetMapping("/{jobId}/status")
    public ApiResponse<AnalysisStatusResponse> getAnalysisStatus(
            @PathVariable String jobId
    ) {
        AnalysisStatusResponse response = analysisQueryService.getStatus(jobId);

        return ApiResponse.success(
                "분석 상태 조회가 완료되었습니다.",
                response
        );
    }

    @PostMapping("/{jobId}/run")
    public ApiResponse<AnalysisStatusResponse> runAnalysis(
            @PathVariable String jobId,
            @RequestBody(required = false) AnalysisRunRequest request
    ) {
        AnalysisStatusResponse response = analysisCommandService.runAnalysis(jobId);

        return ApiResponse.success(
                "분석 실행이 완료되었습니다.",
                response
        );
    }

    @GetMapping("/{jobId}/result")
    public ApiResponse<AnalysisResultResponse> getAnalysisResult(
            @PathVariable String jobId
    ) {
        AnalysisResultResponse response = resultQueryService.getFinalResult(jobId);

        return ApiResponse.success(
                "분석 결과 조회가 완료되었습니다.",
                response
        );
    }
}