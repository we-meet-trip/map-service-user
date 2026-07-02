package map.service.user.domain.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import map.service.user.domain.auth.dto.request.EmailLoginRequest;
import map.service.user.domain.auth.dto.request.EmailSignUpRequest;
import map.service.user.domain.auth.dto.request.KakaoLoginRequest;
import map.service.user.domain.auth.dto.request.TokenRefreshRequest;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.auth.service.AuthService;
import map.service.user.domain.auth.service.KakaoOAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService       authService;
    private final KakaoOAuthService kakaoOAuthService;

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signUp(@Valid @RequestBody EmailSignUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signUp(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody EmailLoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/kakao")
    public ResponseEntity<String> kakaoLoginUrl() {
        return ResponseEntity.ok("kakao-oauth-url");
    }

    @PostMapping("/kakao/callback")
    public ResponseEntity<AuthResponse> kakaoCallback(@Valid @RequestBody KakaoLoginRequest request) {
        return ResponseEntity.ok(kakaoOAuthService.processLogin(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) TokenRefreshRequest body) {
        String accessToken = extractBearerToken(authHeader);
        String refreshToken = (body != null) ? body.getRefreshToken() : null;
        authService.logout(accessToken, refreshToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(authService.refreshTokens(request));
    }

    private String extractBearerToken(String header) {
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return header;
    }
}
