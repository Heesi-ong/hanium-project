package com.hanium.presentation.global.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.config.UserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class UserRateLimitFilterTest {

    private static final Long USER_ID = 42L;

    private UserRateLimiter userRateLimiter;
    private UserRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        userRateLimiter = mock(UserRateLimiter.class);
        filter = new UserRateLimitFilter(userRateLimiter, new ObjectMapper().findAndRegisterModules());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("user@example.com", null);
        authentication.setDetails(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appliesExpectedBucketsToResultsTokenAndStatusRequests() throws Exception {
        allow("results-query");
        allow("video-access-token");
        allow("job-status-poll");

        doFilter("GET", "/api/results");
        doFilter("GET", "/api/results/20260714120000-abcdef01");
        doFilter("POST", "/api/results/20260714120000-abcdef01/video-access-token");
        doFilter("GET", "/api/analysis/20260714120000-abcdef01/status");
        doFilter("GET", "/api/analysis/20260714120000-abcdef01/progress");

        verify(userRateLimiter, times(2)).tryConsume("results-query", USER_ID);
        verify(userRateLimiter).tryConsume("video-access-token", USER_ID);
        verify(userRateLimiter, times(2)).tryConsume("job-status-poll", USER_ID);
        verifyNoMoreInteractions(userRateLimiter);
    }

    @Test
    void ignoresUnrelatedAndVideoStreamingGetRequests() throws Exception {
        doFilter("GET", "/api/health");
        doFilter("GET", "/api/results/20260714120000-abcdef01/video");

        verify(userRateLimiter, never()).tryConsume(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void returnsTooManyRequestsWhenBucketIsExhausted() throws Exception {
        when(userRateLimiter.tryConsume("results-query", USER_ID)).thenReturn(false);
        MockHttpServletResponse response = doFilter("GET", "/api/results");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS");
    }

    private void allow(String bucketName) {
        when(userRateLimiter.tryConsume(bucketName, USER_ID)).thenReturn(true);
    }

    private MockHttpServletResponse doFilter(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
