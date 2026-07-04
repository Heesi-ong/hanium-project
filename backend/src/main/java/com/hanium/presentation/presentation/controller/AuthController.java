package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.JwtBlacklist;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.config.SecurityConfig.JwtTokenProvider;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtBlacklist jwtBlacklist;
    private final UserRateLimiter userRateLimiter;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtBlacklist jwtBlacklist,
            UserRateLimiter userRateLimiter
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtBlacklist = jwtBlacklist;
        this.userRateLimiter = userRateLimiter;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<?>> signup(
            @Valid @RequestBody AuthRequest request
    ) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("이미 가입된 이메일입니다."));
        }

        try {
            User user = userRepository.save(User.create(
                    email,
                    passwordEncoder.encode(request.password())
            ));

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ApiResponse.success(
                            "회원가입이 완료되었습니다.",
                            AuthUserResponse.from(user)
                    ));
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("이미 가입된 이메일입니다."));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(
            @Valid @RequestBody AuthRequest request
    ) {
        String email = normalizeEmail(request.email());
        if (!userRateLimiter.tryConsume("login", email)) {
            ErrorCode errorCode = ErrorCode.TOO_MANY_REQUESTS;
            return ResponseEntity
                    .status(errorCode.getStatus())
                    .body(ApiResponse.fail(errorCode.getMessage()));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("이메일 또는 비밀번호가 올바르지 않습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(
                "로그인이 완료되었습니다.",
                LoginResponse.from(user, jwtTokenProvider.createToken(user))
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<?>> logout(HttpServletRequest request) {
        resolveBearerToken(request)
                .flatMap(token -> jwtTokenProvider.extractExpiration(token)
                        .map(expiration -> new TokenExpiration(token, expiration)))
                .ifPresent(tokenExpiration -> {
                    Duration ttl = Duration.between(Instant.now(), tokenExpiration.expiration());
                    jwtBlacklist.blacklist(tokenExpiration.token(), ttl);
                });

        return ResponseEntity.ok(ApiResponse.success("로그아웃이 완료되었습니다."));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
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

    public record AuthRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해야 합니다.")
            String password
    ) {
    }

    public record AuthUserResponse(
            Long id,
            String email
    ) {

        public static AuthUserResponse from(User user) {
            return new AuthUserResponse(user.getId(), user.getEmail());
        }
    }

    public record LoginResponse(
            String tokenType,
            String accessToken,
            AuthUserResponse user
    ) {

        public static LoginResponse from(User user, String accessToken) {
            return new LoginResponse(
                    "Bearer",
                    accessToken,
                    AuthUserResponse.from(user)
            );
        }
    }

    private record TokenExpiration(
            String token,
            Instant expiration
    ) {
    }
}
