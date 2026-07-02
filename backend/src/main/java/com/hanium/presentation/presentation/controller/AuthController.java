package com.hanium.presentation.presentation.controller;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.global.config.SecurityConfig.JwtTokenProvider;
import com.hanium.presentation.global.response.ApiResponse;
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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
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

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
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
}
