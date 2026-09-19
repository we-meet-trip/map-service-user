package map.service.user.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import map.service.user.domain.auth.dto.request.KakaoLoginRequest;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.auth.dto.response.KakaoTokenInfoResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 카카오 로그인.
 *
 * 앱이 카카오 SDK 로 로그인해 액세스 토큰을 받아 오고, 서버는 그 토큰을 검증한 뒤
 * 우리 계정에 연결한다. 인가 주소 조립과 인가 코드 교환은 서버가 하지 않는다 —
 * 카카오톡 앱으로 직접 전환하는 방식에는 리다이렉트가 없기 때문이다.
 *
 * 토큰을 받았다고 바로 믿지 않는다. 카카오 회원번호는 앱마다 따로 매겨지므로,
 * 다른 앱에서 발급된 토큰을 그대로 받아들이면 그 앱의 회원번호가 우리 쪽 같은 번호의
 * 계정으로 이어질 수 있다. 그래서 토큰에 묶인 앱 번호를 먼저 대조한다.
 */
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

    /** 동의항목: 닉네임만 요청(기본 제공, 비즈앱/심사 불요). 이메일은 provider_id 전용 정책상 미요청. */
    private static final String SCOPE = "profile_nickname";

    @Transactional
    public AuthResponse processLogin(KakaoLoginRequest request) {
        if (kakaoProperties.getAppId() == null) {
            throw new CustomException(ErrorCode.KAKAO_NOT_CONFIGURED);
        }
        String accessToken = request.getAccessToken();
        long verifiedUserId = verifyAccessToken(accessToken);

        KakaoUserInfoResponse userInfo = fetchUserInfo(accessToken);
        if (userInfo == null || userInfo.getId() == null || userInfo.getId() <= 0) {
            throw new CustomException(ErrorCode.KAKAO_USER_INFO_FAILED);
        }
        // 두 조회가 같은 토큰을 쓰는데 회원번호가 다르면 응답을 신뢰할 수 없다.
        if (userInfo.getId() != verifiedUserId) {
            throw new CustomException(ErrorCode.KAKAO_TOKEN_REJECTED);
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
                    .scope(SCOPE)
                    .connectedAt(parseConnectedAt(userInfo.getConnectedAt()))
                    .build();

            try {
                oauthAccountRepository.saveAndFlush(newOAuth);
            } catch (DataIntegrityViolationException e) {
                // A constraint failure aborts the PostgreSQL transaction. Roll back the
                // new user as well; a fresh login can find the winning provider identity.
                throw new CustomException(ErrorCode.KAKAO_ACCOUNT_CONFLICT);
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

    /**
     * 토큰이 이 앱에 발급된 것인지 확인하고 그 토큰의 회원번호를 돌려준다.
     *
     * 카카오는 토큰이 무효할 때 401 을, 자기 쪽 일시 장애일 때 400 을 준다. 둘을 같게
     * 다루면 장애 중에 멀쩡한 사용자를 로그아웃시키게 되므로, 무효는 거절(401)로
     * 장애는 잠시 후 재시도(503)로 나눈다.
     */
    private long verifyAccessToken(String accessToken) {
        KakaoTokenInfoResponse info;
        try {
            info = kakaoRestClient.get()
                    .uri(kakaoProperties.getTokenInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoTokenInfoResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                log.warn("카카오 액세스 토큰 거절 (401)");
                throw new CustomException(ErrorCode.KAKAO_TOKEN_REJECTED);
            }
            log.warn("카카오 토큰 정보 조회 실패 (status={})", e.getStatusCode().value());
            throw new CustomException(ErrorCode.KAKAO_TOKEN_INFO_UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("카카오 토큰 정보 조회 실패 ({})", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.KAKAO_TOKEN_INFO_UNAVAILABLE);
        }
        if (info == null || info.getId() == null || info.getId() <= 0 || info.getAppId() == null) {
            throw new CustomException(ErrorCode.KAKAO_TOKEN_INFO_UNAVAILABLE);
        }
        if (!kakaoProperties.getAppId().equals(info.getAppId())) {
            // 다른 앱에서 발급된 토큰이다. 그 앱의 회원번호로 우리 계정을 열어 줄 수는 없다.
            log.warn("카카오 액세스 토큰의 앱 번호 불일치");
            throw new CustomException(ErrorCode.KAKAO_TOKEN_REJECTED);
        }
        return info.getId();
    }

    private KakaoUserInfoResponse fetchUserInfo(String accessToken) {
        try {
            return kakaoRestClient.get()
                    .uri(kakaoProperties.getUserInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);
        } catch (RestClientException e) {
            log.warn("카카오 사용자 정보 조회 실패 ({})", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.KAKAO_USER_INFO_FAILED);
        }
    }

    private User findOrCreateUser(KakaoUserInfoResponse userInfo) {
        String email    = userInfo.getEmail();
        String nickname = resolveNickname(userInfo);

        if (email != null) {
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                throw new CustomException(ErrorCode.KAKAO_ACCOUNT_CONFLICT);
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
            // Never select another account by email, including concurrent signup.
            throw new CustomException(ErrorCode.KAKAO_ACCOUNT_CONFLICT);
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
