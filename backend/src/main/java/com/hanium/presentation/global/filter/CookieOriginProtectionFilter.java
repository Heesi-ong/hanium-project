package com.hanium.presentation.global.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.JwtCookieSupport;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HttpOnly 인증 쿠키가 첨부된 브라우저의 상태 변경 요청을 허용된 origin으로 제한한다.
 * 명시적 bearer 인증과 Fetch Metadata가 없는 비브라우저 클라이언트는 기존 계약을 유지한다.
 */
public class CookieOriginProtectionFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = HttpHeaders.AUTHORIZATION;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    private static final String BROWSER_USER_AGENT_PREFIX = "Mozilla/";

    private final Set<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    public CookieOriginProtectionFilter(String allowedOriginsCsv, ObjectMapper objectMapper) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split("\\s*,\\s*"))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/")
                || isSafeMethod(request.getMethod())
                || !hasAccessTokenCookie(request)
                || hasBearerAuthorization(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (isTrustedBrowserRequest(request) || isNonBrowserRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        ErrorCode errorCode = ErrorCode.AUTH_ORIGIN_FORBIDDEN;
        response.setStatus(errorCode.getStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
    }

    private boolean isSafeMethod(String method) {
        return HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)
                || HttpMethod.TRACE.matches(method);
    }

    private boolean hasAccessTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }

        return Arrays.stream(cookies)
                .anyMatch(cookie -> JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank());
    }

    private boolean hasBearerAuthorization(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        return authorization != null
                && authorization.startsWith(BEARER_PREFIX)
                && !authorization.substring(BEARER_PREFIX.length()).isBlank();
    }

    private boolean isTrustedBrowserRequest(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            return allowedOrigins.contains(origin);
        }

        return SAME_ORIGIN.equals(request.getHeader(SEC_FETCH_SITE_HEADER));
    }

    private boolean isNonBrowserRequest(HttpServletRequest request) {
        if (request.getHeader(SEC_FETCH_SITE_HEADER) != null) {
            return false;
        }

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        return userAgent == null || !userAgent.startsWith(BROWSER_USER_AGENT_PREFIX);
    }
}
