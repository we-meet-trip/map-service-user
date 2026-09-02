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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    /** 동의항목: 닉네임만 요청(기본 제공, 비즈앱/심사 불요). 이메일은 provider_id 전용 정책상 미요청. */
    private static final String SCOPE = "profile_nickname";

    /**
     * Kakao 인가 요청 URL 을 조립한다.
     * redirect_uri 는 토큰 교환(exchangeCodeForToken)과 동일한 kakaoProperties.getRedirectUri()
     * 를 사용해 byte 단위 일치를 보장한다(불일치=KOE006). client_id 는 공개 식별자(REST 키)다.
     *
     * @param state 앱이 생성한 CSRF 방지 랜덤값(앱이 콜백에서 재검증)
     */
    public String buildAuthorizeUrl(String state) {
        // 발급 식별자가 비어 있어도 주소는 만들어진다. 그 주소를 받은 앱은
        // 바깥 브라우저를 열고, 사용자는 카카오 오류 화면을 보고, 앱은 돌아오지
        // 않는 응답을 기다리다 한참 뒤에야 시간 초과로 접힌다. 설정이 덜 된
        // 것을 사용자가 잘못한 것처럼 보여 주는 셈이라, 여기서 바로 끊는다.
        String clientId = kakaoProperties.getClientId();
        if (clientId == null || clientId.isBlank()) {
            throw new CustomException(ErrorCode.KAKAO_NOT_CONFIGURED);
        }
        return kakaoProperties.getAuthorizeUri()
                + "?client_id="     + enc(clientId)
                + "&redirect_uri="  + enc(kakaoProperties.getRedirectUri())
                + "&response_type=code"
                + "&scope="         + enc(SCOPE)
                + "&state="         + enc(state);
    }

    /**
     * Kakao https 콜백(GET)을 앱 커스텀 스킴으로 재작성할 Location 문자열을 만든다.
     * 리다이렉트 대상 스킴은 설정값(kakao.app-callback-scheme)으로 고정되어 요청 입력을 받지
     * 않으므로 open-redirect 위험이 없다. 성공 시 code/state, 실패 시 error/error_description 을
     * URL-encode 하여 쿼리로 전달한다.
     */
    public String buildAppCallbackLocation(String code, String state, String error, String errorDescription) {
        StringBuilder sb = new StringBuilder(kakaoProperties.getAppCallbackScheme());
        sb.append(kakaoProperties.getAppCallbackScheme().contains("?") ? '&' : '?');
        if (error != null && !error.isBlank()) {
            sb.append("error=").append(enc(error));
            if (errorDescription != null && !errorDescription.isBlank()) {
                sb.append("&error_description=").append(enc(errorDescription));
            }
        } else {
            sb.append("code=").append(enc(code));
            if (state != null) {
                sb.append("&state=").append(enc(state));
            }
        }
        return sb.toString();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

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
            // client_secret 은 2025-12 개편 이후 REST 키에 기본 활성이나, 콘솔에서 비활성한 앱도
            // 있으므로 값이 있을 때만 포함한다(빈 값 전송 시 Kakao 가 오류로 처리할 수 있음).
            String clientSecret = kakaoProperties.getClientSecret();
            if (clientSecret != null && !clientSecret.isBlank()) {
                params.add("client_secret", clientSecret);
            }
            // redirect_uri 는 authorize 단계와 byte 단위 동일해야 한다(불일치=KOE006/invalid_grant).
            // buildAuthorizeUrl 도 동일한 kakaoProperties.getRedirectUri() 를 사용한다.
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
