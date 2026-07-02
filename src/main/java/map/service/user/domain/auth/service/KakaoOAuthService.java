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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

            try {
                oauthAccountRepository.save(newOAuth);
            } catch (DataIntegrityViolationException e) {
                // 동시 로그인 레이스 컨디션 — 이미 저장된 계정 재조회
                user = oauthAccountRepository
                        .findByProviderAndProviderUserId(AuthProvider.KAKAO, userInfo.getId())
                        .map(OAuthAccount::getUser)
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
            }
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

        try {
            return userRepository.save(newUser);
        } catch (DataIntegrityViolationException e) {
            // 동시 회원가입 레이스 컨디션 — 이미 저장된 사용자 재조회
            return userRepository.findByEmail(email)
                    .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
        }
    }

    private String resolveNickname(KakaoUserInfoResponse userInfo) {
        String nickname = userInfo.getNickname();
        if (nickname != null && !nickname.isBlank()) return nickname;
        String idStr = userInfo.getId().toString();
        return "kakao_" + idStr.substring(0, Math.min(8, idStr.length()));
    }

    private OffsetDateTime parseConnectedAt(String connectedAt) {
        if (connectedAt == null) return null;
        try {
            return OffsetDateTime.parse(connectedAt);
        } catch (Exception e) {
            return null;
        }
    }
}
