package com.hanium.presentation.presentation.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hanium.presentation.application.auth.PasswordResetService;
import com.hanium.presentation.domain.user.TermsVersion;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.JwtBlacklist;
import com.hanium.presentation.global.config.JwtCookieSupport;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.config.SecurityConfig.JwtTokenProvider;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PASSWORD_RESET_REQUEST_MESSAGE =
            "입력한 이메일로 비밀번호 재설정 안내를 보냈습니다. 등록되지 않은 이메일이어도 동일하게 처리됩니다.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtBlacklist jwtBlacklist;
    private final UserRateLimiter userRateLimiter;
    private final JwtCookieSupport jwtCookieSupport;
    private final PasswordResetService passwordResetService;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            JwtBlacklist jwtBlacklist,
            UserRateLimiter userRateLimiter,
            JwtCookieSupport jwtCookieSupport,
            PasswordResetService passwordResetService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtBlacklist = jwtBlacklist;
        this.userRateLimiter = userRateLimiter;
        this.jwtCookieSupport = jwtCookieSupport;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<?>> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest servletRequest
    ) {
        String clientIp = resolveClientIp(servletRequest);
        if (!userRateLimiter.tryConsume("signup", clientIp)) {
            ErrorCode errorCode = ErrorCode.TOO_MANY_REQUESTS;
            return ResponseEntity
                    .status(errorCode.getStatus())
                    .body(ApiResponse.fail(errorCode.getMessage()));
        }

        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("이미 가입된 이메일입니다."));
        }

        try {
            User user = userRepository.save(User.create(
                    email,
                    passwordEncoder.encode(request.password()),
                    LocalDateTime.now(),
                    TermsVersion.CURRENT
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
            @Valid @RequestBody AuthRequest request,
            HttpServletRequest servletRequest
    ) {
        String clientIp = resolveClientIp(servletRequest);
        if (!userRateLimiter.tryConsume("login-ip", clientIp)) {
            ErrorCode errorCode = ErrorCode.TOO_MANY_REQUESTS;
            return ResponseEntity
                    .status(errorCode.getStatus())
                    .body(ApiResponse.fail(errorCode.getMessage()));
        }

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

        String accessToken = jwtTokenProvider.createToken(user);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookieSupport
                        .createAccessTokenCookie(accessToken, jwtTokenProvider.getExpiration())
                        .toString())
                .body(ApiResponse.success(
                        "로그인이 완료되었습니다.",
                        LoginResponse.from(user, accessToken)
                ));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<?>> logout(HttpServletRequest request) {
        resolveAccessToken(request)
                .flatMap(token -> jwtTokenProvider.extractExpiration(token)
                        .map(expiration -> new TokenExpiration(token, expiration)))
                .ifPresent(tokenExpiration -> {
                    Duration ttl = Duration.between(Instant.now(), tokenExpiration.expiration());
                    jwtBlacklist.blacklist(tokenExpiration.token(), ttl);
                });

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, jwtCookieSupport.expireAccessTokenCookie().toString())
                .body(ApiResponse.success("로그아웃이 완료되었습니다."));
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<?>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest servletRequest
    ) {
        String email = normalizeEmail(request.email());
        String rateLimitKey = resolveClientIp(servletRequest) + ":" + email;
        if (!userRateLimiter.tryConsume("password-reset-request", rateLimitKey)) {
            ErrorCode errorCode = ErrorCode.TOO_MANY_REQUESTS;
            return ResponseEntity
                    .status(errorCode.getStatus())
                    .body(ApiResponse.fail(errorCode.getMessage()));
        }

        passwordResetService.requestPasswordReset(email);
        return ResponseEntity.ok(ApiResponse.success(PASSWORD_RESET_REQUEST_MESSAGE));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<?>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request
    ) {
        boolean confirmed = passwordResetService.confirmPasswordReset(
                request.token().trim(),
                request.newPassword()
        );

        if (!confirmed) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.fail("비밀번호 재설정 링크가 만료되었거나 이미 사용되었습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success("비밀번호가 재설정되었습니다. 새 비밀번호로 로그인해 주세요."));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(Authentication authentication) {
        User user = userRepository.findById(getCurrentUserId(authentication))
                .orElseThrow(() -> new IllegalStateException("인증된 사용자를 찾을 수 없습니다."));

        return ApiResponse.success(
                "인증된 사용자 조회가 완료되었습니다.",
                AuthUserResponse.from(user)
        );
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }

    private Long getCurrentUserId(Authentication authentication) {
        Object details = authentication.getDetails();
        if (details instanceof Long userId) {
            return userId;
        }

        if (details instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException("인증 정보에서 사용자 id를 찾을 수 없습니다.");
    }

    private Optional<String> resolveAccessToken(HttpServletRequest request) {
        if (request.getHeader(AUTHORIZATION_HEADER) == null) {
            return resolveCookieToken(request);
        }

        return resolveBearerToken(request);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해야 합니다.")
            String password
    ) {
    }

    public record SignupRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해야 합니다.")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                    message = "비밀번호는 영문자와 숫자를 각각 1자 이상 포함해야 합니다."
            )
            String password,

            @NotNull(message = "개인정보처리방침 및 이용약관 동의는 필수입니다.")
            @AssertTrue(message = "개인정보처리방침 및 이용약관에 동의해야 합니다.")
            Boolean agreedToTerms
    ) {
    }

    public record PasswordResetRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email
    ) {
    }

    public record PasswordResetConfirmRequest(
            @NotBlank(message = "토큰은 필수입니다.")
            String token,

            @NotBlank(message = "새 비밀번호는 필수입니다.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하로 입력해야 합니다.")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                    message = "비밀번호는 영문자와 숫자를 각각 1자 이상 포함해야 합니다."
            )
            String newPassword
    ) {
    }

    public record AuthUserResponse(
            Long id,
            String email,
            boolean onboardingCompleted
    ) {

        public static AuthUserResponse from(User user) {
            return new AuthUserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getOnboardingCompletedAt() != null
            );
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
