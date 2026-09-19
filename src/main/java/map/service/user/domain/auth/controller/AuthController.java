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

    /**
     * 앱이 카카오 SDK 로 받아 온 액세스 토큰을 우리 계정에 연결한다.
     *
     * 앱은 카카오톡 앱으로 직접 전환해 로그인하므로 인가 주소도 콜백 리다이렉트도
     * 서버를 거치지 않는다. 서버는 받은 토큰이 우리 앱에 발급된 것인지 확인한 뒤에만
     * 사용자 정보를 읽는다.
     */
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
