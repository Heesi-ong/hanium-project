package com.hanium.presentation.global.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class UserRateLimitFilter extends OncePerRequestFilter {

    private static final String UPLOAD_PATH = "/api/analysis/upload";
    private static final String ANALYSIS_BUCKET = "analysis";
    private static final String UPLOAD_BUCKET = "upload";

    private final UserRateLimiter userRateLimiter;
    private final ObjectMapper objectMapper;

    public UserRateLimitFilter(
            UserRateLimiter userRateLimiter,
            ObjectMapper objectMapper
    ) {
        this.userRateLimiter = userRateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String bucketName = resolveBucketName(request);

        if (bucketName == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Long userId = getCurrentUserId();
        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!userRateLimiter.tryConsume(bucketName, userId)) {
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveBucketName(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return null;
        }

        String path = request.getRequestURI();

        if (UPLOAD_PATH.equals(path)) {
            return UPLOAD_BUCKET;
        }

        if (path.matches("^/api/analysis/\\d{14}-[0-9a-f]{8}/(run|retry)$")) {
            return ANALYSIS_BUCKET;
        }

        return null;
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        Object details = authentication.getDetails();
        if (details instanceof Long userId) {
            return userId;
        }

        if (details instanceof Number number) {
            return number.longValue();
        }

        return null;
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.TOO_MANY_REQUESTS;

        response.setStatus(errorCode.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
    }
}
