package map.service.user.domain.auth.service;

import lombok.RequiredArgsConstructor;
import map.service.user.domain.auth.dto.request.EmailLoginRequest;
import map.service.user.domain.auth.dto.request.EmailSignUpRequest;
import map.service.user.domain.auth.dto.request.TokenRefreshRequest;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.DeviceType;
import map.service.user.domain.user.entity.RefreshToken;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.entity.UserDevice;
import map.service.user.domain.user.repository.RefreshTokenRepository;
import map.service.user.domain.user.repository.UserDeviceRepository;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository         userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserDeviceRepository   userDeviceRepository;
    private final PasswordEncoder        passwordEncoder;
    private final JwtService             jwtService;
    private final TokenRevokeService     tokenRevokeService;

    // ── 이메일 회원가입 ───────────────────────────────────────────────────────

    @Transactional
    public AuthResponse signUp(EmailSignUpRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(request.getEmail())
                .nickname(request.getNickname())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .build();

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 체크 후 save 사이 레이스 컨디션으로 UNIQUE 위반 시 409
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        saveDeviceIfPresent(user, request.getDeviceToken(), request.getDeviceType());
        return buildAuthResponse(user);
    }

    // ── 이메일 로그인 ─────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(EmailLoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        saveDeviceIfPresent(user, request.getDeviceToken(), request.getDeviceType());
        return buildAuthResponse(user);
    }

    // ── 로그아웃 ──────────────────────────────────────────────────────────────

    @Transactional
    public void logout(String rawAccessToken, String rawRefreshToken) {
        jwtService.blacklistAccessToken(rawAccessToken);

        if (rawRefreshToken != null) {
            String hash = sha256Hex(rawRefreshToken);
            refreshTokenRepository.findByTokenHash(hash)
                    .ifPresent(RefreshToken::revoke);
        }
    }

    // ── Refresh Token 회전 ────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refreshTokens(TokenRefreshRequest request) {
        String hash = sha256Hex(request.getRefreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));

        if (stored.isRevoked()) {
            // 재사용 탐지: REQUIRES_NEW로 즉시 커밋 (메인 트랜잭션 롤백과 무관하게 폐기 보장)
            tokenRevokeService.revokeAllForUser(stored.getUser().getId());
            throw new CustomException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }

        if (stored.isExpired()) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        stored.revoke();  // 기존 토큰 폐기 (회전)
        return buildAuthResponse(stored.getUser());
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse buildAuthResponse(User user) {
        String accessToken  = jwtService.generateAccessToken(user);
        String rawRefresh   = jwtService.generateRawRefreshToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(sha256Hex(rawRefresh))
                .expiresAt(OffsetDateTime.now().plusSeconds(jwtService.getRefreshTokenExpirySeconds()))
                .build();

        refreshTokenRepository.save(refreshToken);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefresh)
                .accessTokenExpiresIn(jwtService.getAccessTokenExpirySeconds())
                .refreshTokenExpiresIn(jwtService.getRefreshTokenExpirySeconds())
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .nickname(user.getNickname())
                        .profileImageUrl(user.getProfileImageUrl())
                        .authProvider(user.getAuthProvider())
                        .emailVerified(user.isEmailVerified())
                        .build())
                .build();
    }

    private void saveDeviceIfPresent(User user, String deviceToken, DeviceType deviceType) {
        if (deviceToken != null && deviceType != null
                && !userDeviceRepository.existsByDeviceToken(deviceToken)) {
            userDeviceRepository.save(UserDevice.builder()
                    .user(user)
                    .deviceToken(deviceToken)
                    .deviceType(deviceType)
                    .build());
        }
    }

    /** SHA-256 hex 해시 (refresh token 저장용) */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
