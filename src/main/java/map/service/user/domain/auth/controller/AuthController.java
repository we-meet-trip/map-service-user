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

import java.net.URI;
import java.util.Map;

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
     * Kakao 인가 요청 URL 을 조립해 반환한다. 클라이언트(앱)가 생성한 state 를 받아 URL 에 실어
     * 주고, 앱은 이 URL 을 커스텀탭으로 열어 로그인·동의를 진행한다. REST 키는 서버에만 존재한다.
     */
    @GetMapping("/kakao")
    public ResponseEntity<Map<String, String>> kakaoLoginUrl(@RequestParam String state) {
        return ResponseEntity.ok(Map.of("authorizeUrl", kakaoOAuthService.buildAuthorizeUrl(state)));
    }

    /**
     * Kakao 가 https 콜백(GET)으로 302 하는 인가 코드를 앱 커스텀 스킴으로 재-302 바운스한다.
     * Kakao 콘솔은 커스텀 스킴 Redirect URI 를 허용하지 않으므로(https 만) 이 서버 콜백이
     * 필요하다. 리다이렉트 대상 스킴은 설정값으로 고정되어 open-redirect 위험이 없다.
     */
    @GetMapping("/kakao/callback")
    public ResponseEntity<Void> kakaoCallbackBounce(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {
        String location = kakaoOAuthService.buildAppCallbackLocation(code, state, error, errorDescription);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
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
