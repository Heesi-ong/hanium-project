package com.hanium.presentation.global.config;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.filter.UserRateLimitFilter;
import com.hanium.presentation.global.properties.ApiDocsProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
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
import java.time.ZoneId;
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
            UserRateLimitFilter userRateLimitFilter,
            ApiDocsProperties apiDocsProperties
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                (request, response, authException) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                        )
                )
                .authorizeHttpRequests(auth -> {
                    auth
                            .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                            .requestMatchers(
                                    HttpMethod.POST,
                                    "/api/auth/signup",
                                    "/api/auth/login",
                                    "/api/auth/logout",
                                    "/api/auth/password-reset/request",
                                    "/api/auth/password-reset/confirm"
                            ).permitAll()
                            .requestMatchers("/api/health", "/api/health/**").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/results/*/video").permitAll();

                    if (apiDocsProperties.publicEnabled()) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll();
                    } else {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .denyAll();
                    }

                    auth
                            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                            .requestMatchers("/api/admin/**").hasRole("ADMIN")
                            .requestMatchers("/api/**").authenticated()
                            .anyRequest().permitAll();
                })
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
            // 운영(prod) 환경에서는 반드시 SECURITY_JWT_SECRET 환경변수로 강력한 값을 주입해야 합니다.
            // 아래 기본값은 로컬 개발용이며 운영에서 그대로 사용하면 안 됩니다.
            @Value("${security.jwt.secret:presentation-coaching-local-jwt-secret-change-me-2026}") String secret,
            @Value("${security.jwt.expiration-minutes:30}") long expirationMinutes
    ) {
        return new JwtTokenProvider(secret, Duration.ofMinutes(expirationMinutes));
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserRepository userRepository,
            JwtBlacklist jwtBlacklist
    ) {
        return new JwtAuthenticationFilter(jwtTokenProvider, userRepository, jwtBlacklist);
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

        public Optional<Instant> extractExpiration(String token) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(signingKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                Date expiration = claims.getExpiration();
                if (expiration == null) {
                    return Optional.empty();
                }

                return Optional.of(expiration.toInstant());
            } catch (JwtException | IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        public Optional<Instant> extractIssuedAt(String token) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(signingKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                Date issuedAt = claims.getIssuedAt();
                if (issuedAt == null) {
                    return Optional.empty();
                }

                return Optional.of(issuedAt.toInstant());
            } catch (JwtException | IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        public Duration getExpiration() {
            return expiration;
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
        private final JwtBlacklist jwtBlacklist;

        public JwtAuthenticationFilter(
                JwtTokenProvider jwtTokenProvider,
                UserRepository userRepository,
                JwtBlacklist jwtBlacklist
        ) {
            this.jwtTokenProvider = jwtTokenProvider;
            this.userRepository = userRepository;
            this.jwtBlacklist = jwtBlacklist;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            Optional<String> bearerToken = resolveBearerToken(request);
            if (bearerToken.isPresent() && jwtBlacklist.isBlacklisted(bearerToken.get())) {
                filterChain.doFilter(request, response);
                return;
            }

            bearerToken.ifPresent(token -> jwtTokenProvider.extractEmail(token)
                    .flatMap(userRepository::findByEmail)
                    .filter(user -> isTokenIssuedAfterPasswordChange(token, user))
                    .filter(user -> !user.isSuspended())
                    .ifPresent(this::authenticate));

            filterChain.doFilter(request, response);
        }

        private boolean isTokenIssuedAfterPasswordChange(String token, User user) {
            if (user.getPasswordChangedAt() == null) {
                return true;
            }

            Optional<Instant> issuedAt = jwtTokenProvider.extractIssuedAt(token);
            if (issuedAt.isEmpty()) {
                return false;
            }

            Instant passwordChangedAt = user.getPasswordChangedAt()
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
            return !issuedAt.get().isBefore(passwordChangedAt);
        }

        private Optional<String> resolveBearerToken(HttpServletRequest request) {
            String authorization = request.getHeader(AUTHORIZATION_HEADER);
            if (authorization == null) {
                return resolveCookieToken(request);
            }

            if (!authorization.startsWith(BEARER_PREFIX)) {
                return Optional.empty();
            }

            String token = authorization.substring(BEARER_PREFIX.length()).trim();
            if (token.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(token);
        }

        private Optional<String> resolveCookieToken(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return Optional.empty();
            }

            for (Cookie cookie : cookies) {
                if (JwtCookieSupport.ACCESS_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                    String token = cookie.getValue();
                    if (token != null && !token.isBlank()) {
                        return Optional.of(token.trim());
                    }
                }
            }

            return Optional.empty();
        }

        private void authenticate(User user) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user.getEmail(),
                            null,
                            resolveAuthorities(user)
                    );
            authentication.setDetails(user.getId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        private List<SimpleGrantedAuthority> resolveAuthorities(User user) {
            if (user.isAdmin()) {
                return List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")
                );
            }

            return USER_AUTHORITIES;
        }
    }
}
