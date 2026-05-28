package map.service.user.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import map.service.user.domain.auth.dto.request.KakaoLoginRequest;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.auth.dto.response.KakaoTokenResponse;
import map.service.user.domain.auth.dto.response.KakaoUserInfoResponse;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.OAuthAccount;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.entity.UserDevice;
import map.service.user.domain.user.repository.OAuthAccountRepository;
import map.service.user.domain.user.repository.UserDeviceRepository;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.config.KakaoProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KakaoOAuthService {

    private final KakaoProperties        kakaoProperties;
    private final UserRepository         userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final UserDeviceRepository   userDeviceRepository;
    private final AuthService            authService;
    private final RestClient             kakaoRestClient;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * 카카오 인가 코드를 받아 로그인 처리 후 JWT 반환.
     *
     * 흐름:
     *   1. 인가 코드 → 카카오 access token 교환
     *   2. 카카오 사용자 정보 조회
     *   3. oauth_accounts에서 기존 사용자 조회
     *      - 있으면: 기존 연동 정보 사용 후 JWT 발급
     *      - 없으면: 이메일로 기존 users 조회 → 연동 또는 신규 생성
     *   4. 디바이스 등록 (선택)
     */
    @Transactional
    public AuthResponse processLogin(KakaoLoginRequest request) {
        KakaoTokenResponse kakaoToken = exchangeCodeForToken(request.getCode());
        if (kakaoToken == null || kakaoToken.getAccessToken() == null) {
            throw new CustomException(ErrorCode.KAKAO_TOKEN_EXCHANGE_FAILED);
        }
        KakaoUserInfoResponse userInfo = fetchUserInfo(kakaoToken.getAccessToken());
        if (userInfo == null) {
            throw new CustomException(ErrorCode.KAKAO_USER_INFO_FAILED);
        }

        Optional<OAuthAccount> existingOAuth =
                oauthAccountRepository.findByProviderAndProviderUserId(AuthProvider.KAKAO, userInfo.getId());

        User user;

        if (existingOAuth.isPresent()) {
            user = existingOAuth.get().getUser();
        } else {
            user = findOrCreateUser(userInfo);

            OAuthAccount newOAuth = OAuthAccount.builder()
                    .user(user)
                    .provider(AuthProvider.KAKAO)
                    .providerUserId(userInfo.getId())
                    .scope(kakaoToken.getScope())
                    .connectedAt(parseConnectedAt(userInfo.getConnectedAt()))
                    .build();

            oauthAccountRepository.save(newOAuth);
        }

        if (request.getDeviceToken() != null && request.getDeviceType() != null
                && !userDeviceRepository.existsByDeviceToken(request.getDeviceToken())) {
            userDeviceRepository.save(UserDevice.builder()
                    .user(user)
                    .deviceToken(request.getDeviceToken())
                    .deviceType(request.getDeviceType())
                    .build());
        }

        return authService.buildAuthResponse(user);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private KakaoTokenResponse exchangeCodeForToken(String code) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type",    "authorization_code");
            params.add("client_id",     kakaoProperties.getClientId());
            params.add("client_secret", kakaoProperties.getClientSecret());
            params.add("redirect_uri",  kakaoProperties.getRedirectUri());
            params.add("code",          code);

            return kakaoRestClient.post()
                    .uri(kakaoProperties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
        } catch (RestClientException e) {
            log.error("카카오 토큰 교환 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_TOKEN_EXCHANGE_FAILED);
        }
    }

    private KakaoUserInfoResponse fetchUserInfo(String accessToken) {
        try {
            return kakaoRestClient.get()
                    .uri(kakaoProperties.getUserInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
        } catch (RestClientException e) {
            log.error("카카오 사용자 정보 조회 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_USER_INFO_FAILED);
        }
    }

    private User findOrCreateUser(KakaoUserInfoResponse userInfo) {
        String email    = userInfo.getEmail();
        String nickname = resolveNickname(userInfo);

        if (email != null) {
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return byEmail.get();
            }
        }

        User newUser = User.builder()
                .email(email)
                .nickname(nickname)
                .profileImageUrl(userInfo.getProfileImageUrl())
                .authProvider(AuthProvider.KAKAO)
                .emailVerified(email != null)
                .build();

        return userRepository.save(newUser);
    }

    private String resolveNickname(KakaoUserInfoResponse userInfo) {
        String nickname = userInfo.getNickname();
        if (nickname != null && !nickname.isBlank()) return nickname;
        String idStr = userInfo.getId().toString();
        return "kakao_" + idStr.substring(0, Math.min(8, idStr.length()));
    }

    private LocalDateTime parseConnectedAt(String connectedAt) {
        if (connectedAt == null) return null;
        try {
            return OffsetDateTime.parse(connectedAt).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
