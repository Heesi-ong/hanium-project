package com.hanium.presentation.global.config;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.filter.UserRateLimitFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            UserRateLimitFilter userRateLimitFilter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                ))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/health", "/api/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(userRateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${security.jwt.secret:presentation-coaching-local-jwt-secret-change-me-2026}") String secret,
            @Value("${security.jwt.expiration-minutes:120}") long expirationMinutes
    ) {
        return new JwtTokenProvider(secret, Duration.ofMinutes(expirationMinutes));
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserRepository userRepository
    ) {
        return new JwtAuthenticationFilter(jwtTokenProvider, userRepository);
    }

    public static class JwtTokenProvider {

        private final SecretKey signingKey;
        private final Duration expiration;

        public JwtTokenProvider(String secret, Duration expiration) {
            this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            this.expiration = expiration;
        }

        public String createToken(User user) {
            Instant now = Instant.now();
            Instant expiresAt = now.plus(expiration);

            return Jwts.builder()
                    .subject(user.getEmail())
                    .claim("userId", user.getId())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(expiresAt))
                    .signWith(signingKey)
                    .compact();
        }

        public Optional<String> extractEmail(String token) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(signingKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                return Optional.ofNullable(claims.getSubject());
            } catch (JwtException | IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
    }

    public static class JwtAuthenticationFilter extends OncePerRequestFilter {

        private static final String AUTHORIZATION_HEADER = "Authorization";
        private static final String BEARER_PREFIX = "Bearer ";
        private static final List<SimpleGrantedAuthority> USER_AUTHORITIES = List.of(
                new SimpleGrantedAuthority("ROLE_USER")
        );

        private final JwtTokenProvider jwtTokenProvider;
        private final UserRepository userRepository;

        public JwtAuthenticationFilter(
                JwtTokenProvider jwtTokenProvider,
                UserRepository userRepository
        ) {
            this.jwtTokenProvider = jwtTokenProvider;
            this.userRepository = userRepository;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            resolveBearerToken(request)
                    .flatMap(jwtTokenProvider::extractEmail)
                    .flatMap(userRepository::findByEmail)
                    .ifPresent(this::authenticate);

            filterChain.doFilter(request, response);
        }

        private Optional<String> resolveBearerToken(HttpServletRequest request) {
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
                return Optional.empty();
            }

            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (token.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(token);
        }

        private void authenticate(User user) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getEmail(),
                            null,
                            USER_AUTHORITIES
                    );
            authentication.setDetails(user.getId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    }
}
