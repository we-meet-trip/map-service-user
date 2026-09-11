package map.service.user.domain.auth.service;

import lombok.RequiredArgsConstructor;
import map.service.user.policy.BirthDatePolicy;
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
        var today = java.time.LocalDate.now(BirthDatePolicy.KST);
        BirthDatePolicy.validate(request.getBirthDate(), today);
        if (Boolean.FALSE.equals(BirthDatePolicy.adult(request.getBirthDate(), today))) {
            throw new CustomException(ErrorCode.AGE_RESTRICTED);
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(request.getEmail())
                .nickname(request.getNickname())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                // 가입 화면이 단계별로 받은 개인 정보와 취향. 뒤 단계를 건너뛰면
                // 비어 온다.
                .birthDate(request.getBirthDate())
                .gender(request.getGender())
                .interests(request.getInterests())
                .themes(request.getThemes())
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
        var claims = jwtService.validateAccessToken(rawAccessToken);
        Long uid = jwtService.extractUserId(claims);
        userRepository.findByIdForUpdate(uid)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));
        String sessionId = claims.get("sid", String.class);
        if (rawRefreshToken != null) {
            RefreshToken token = refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken))
                    .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
            if (!uid.equals(token.getUser().getId())) throw new CustomException(ErrorCode.INVALID_TOKEN);
            String refreshSession = token.getSessionId() == null ? token.getTokenHash() : token.getSessionId();
            if (sessionId != null && !sessionId.equals(refreshSession))
                throw new CustomException(ErrorCode.INVALID_TOKEN);
            sessionId = refreshSession;
        }
        if (sessionId != null) refreshTokenRepository.revokeSession(uid, sessionId, OffsetDateTime.now());
        jwtService.blacklistAccessToken(rawAccessToken);
    }

    // Serialize refresh and logout for one user; a reused token revokes its session durably.
    @Transactional(noRollbackFor = CustomException.class)
    public AuthResponse refreshTokens(TokenRefreshRequest request) {
        String hash = sha256Hex(request.getRefreshToken());
        Long uid = refreshTokenRepository.findUserIdByTokenHash(hash)
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        userRepository.findByIdForUpdate(uid)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
        String sessionId = stored.getSessionId() == null ? stored.getTokenHash() : stored.getSessionId();
        if (stored.isRevoked()) {
            refreshTokenRepository.revokeSession(uid, sessionId, OffsetDateTime.now());
            throw new CustomException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        if (stored.isExpired()) throw new CustomException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        stored.revoke();
        return buildAuthResponse(stored.getUser(), sessionId);
    }

    // ── 공통 ──────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse buildAuthResponse(User user) {
        return buildAuthResponse(user, java.util.UUID.randomUUID().toString());
    }

    private AuthResponse buildAuthResponse(User user, String sessionId) {
        String accessToken  = jwtService.generateAccessToken(user, sessionId);
        String rawRefresh   = jwtService.generateRawRefreshToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(sha256Hex(rawRefresh))
                .sessionId(sessionId)
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
