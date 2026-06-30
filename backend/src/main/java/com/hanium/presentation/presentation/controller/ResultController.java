package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.result.ResultQueryService;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/results")
public class ResultController {

    private final ResultQueryService resultQueryService;

    public ResultController(ResultQueryService resultQueryService) {
        this.resultQueryService = resultQueryService;
    }

    @GetMapping
    public ApiResponse<List<ResultSummaryResponse>> getResults() {
        List<ResultSummaryResponse> response = resultQueryService.getResultSummaries();

        return ApiResponse.success(
                "분석 결과 목록 조회가 완료되었습니다.",
                response
        );
    }

    @GetMapping("/{jobId}")
    public ApiResponse<AnalysisResultResponse> getResult(
            @PathVariable String jobId
    ) {
        AnalysisResultResponse response = resultQueryService.getFinalResult(jobId);

        return ApiResponse.success(
                "분석 결과 조회가 완료되었습니다.",
                response
        );
    }
}