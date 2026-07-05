package com.hanium.presentation.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 모든 HTTP 요청에 상관관계 ID(requestId)를 부여하는 필터입니다.
 * 클라이언트가 X-Request-Id 헤더를 보내면 그 값을 그대로 쓰고,
 * 없으면 새 UUID를 생성합니다. 같은 값을 응답 헤더로도 돌려주므로
 * 클라이언트/프록시 로그와 서버 로그를 같은 ID로 이어서 추적할 수 있습니다.
 *
 * 가장 먼저 실행되도록 최고 우선순위로 등록해, 시큐리티 필터를 포함한
 * 이후 모든 처리의 로그에 requestId가 포함되게 합니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);

        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 스레드 풀이 스레드를 재사용하므로, 정리하지 않으면
            // 이전 요청의 requestId가 다음 요청 로그에 남을 수 있습니다.
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }
}
