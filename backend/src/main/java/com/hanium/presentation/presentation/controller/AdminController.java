package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.application.admin.AdminDashboardService;
import com.hanium.presentation.global.response.ApiResponse;
import com.hanium.presentation.presentation.dto.response.AdminStatsResponse;
import com.hanium.presentation.presentation.dto.response.AdminUserSummaryResponse;
import com.hanium.presentation.presentation.dto.response.PagedAdminUserSummaryResponse;
import com.hanium.presentation.presentation.dto.response.PagedResultSummaryResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 API입니다. 이번 Unit까지는 조회 전용(사용자 목록/집계 통계/사용자별 분석 결과 목록)까지만 다룹니다.
 * 계정 정지/강제 탈퇴/결과 삭제 등 관리 액션은 후속 Unit에서 이 컨트롤러에 추가합니다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminDashboardService adminDashboardService;

    public AdminController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping("/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.success("관리자 권한이 확인되었습니다.", "pong");
    }

    @GetMapping("/users")
    public ApiResponse<PagedAdminUserSummaryResponse> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AdminUserSummaryResponse> users = adminDashboardService.getUsers(createPageable(page, size));

        return ApiResponse.success(
                "사용자 목록 조회가 완료되었습니다.",
                PagedAdminUserSummaryResponse.from(users)
        );
    }

    @GetMapping("/stats")
    public ApiResponse<AdminStatsResponse> getStats() {
        return ApiResponse.success(
                "집계 통계 조회가 완료되었습니다.",
                adminDashboardService.getStats()
        );
    }

    @GetMapping("/users/{userId}/results")
    public ApiResponse<PagedResultSummaryResponse> getUserResults(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<ResultSummaryResponse> results = adminDashboardService.getUserResults(
                userId,
                createPageable(page, size)
        );

        return ApiResponse.success(
                "사용자 분석 결과 목록 조회가 완료되었습니다.",
                PagedResultSummaryResponse.from(results)
        );
    }

    private Pageable createPageable(int page, int size) {
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = size <= 0
                ? DEFAULT_PAGE_SIZE
                : Math.min(size, MAX_PAGE_SIZE);

        return PageRequest.of(normalizedPage, normalizedSize);
    }
}
