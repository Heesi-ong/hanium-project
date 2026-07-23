package com.hanium.presentation.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hanium.presentation.domain.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterFailureTest {

    @Test
    void rejectsTokenRequestWithServiceUnavailableWhenRevocationStateCannotBeChecked()
            throws Exception {
        SecurityConfig.JwtTokenProvider tokenProvider = mock(SecurityConfig.JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtBlacklist jwtBlacklist = mock(JwtBlacklist.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SecurityConfig.JwtAuthenticationFilter filter = new SecurityConfig.JwtAuthenticationFilter(
                tokenProvider,
                userRepository,
                jwtBlacklist,
                objectMapper
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);
        when(jwtBlacklist.isBlacklisted("signed-token"))
                .thenThrow(new JwtRevocationUnavailableException(
                        "revocation database down",
                        new IllegalStateException("db down")
                ));
        when(tokenProvider.extractEmail("signed-token"))
                .thenReturn(Optional.of("member@example.com"));

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"AUTH_SESSION_SERVICE_UNAVAILABLE\"");
        verify(filterChain, never()).doFilter(request, response);
        verify(userRepository, never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void doesNotQueryRevocationStoresForMalformedOrExpiredJwt() throws Exception {
        SecurityConfig.JwtTokenProvider tokenProvider = mock(SecurityConfig.JwtTokenProvider.class);
        UserRepository userRepository = mock(UserRepository.class);
        JwtBlacklist jwtBlacklist = mock(JwtBlacklist.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SecurityConfig.JwtAuthenticationFilter filter = new SecurityConfig.JwtAuthenticationFilter(
                tokenProvider,
                userRepository,
                jwtBlacklist,
                objectMapper
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer attacker-controlled-garbage");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);
        when(tokenProvider.extractEmail("attacker-controlled-garbage"))
                .thenReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        verify(jwtBlacklist, never()).isBlacklisted(org.mockito.ArgumentMatchers.anyString());
        verify(userRepository, never()).findByEmail(org.mockito.ArgumentMatchers.anyString());
        verify(filterChain).doFilter(request, response);
    }
}
