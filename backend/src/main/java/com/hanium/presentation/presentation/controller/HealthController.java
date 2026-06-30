package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ApiResponse<Map<String, String>> healthCheck() {
        return ApiResponse.success(
                "백엔드 서버가 정상적으로 실행 중입니다.",
                Map.of(
                        "service", "backend",
                        "status", "ok"
                )
        );
    }
}