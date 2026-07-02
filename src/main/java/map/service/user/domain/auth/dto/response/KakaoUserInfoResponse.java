package map.service.user.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoUserInfoResponse {

    /** 카카오 회원번호 — BIGINT (long) */
    @JsonProperty("id")
    private Long id;

    @JsonProperty("connected_at")
    private String connectedAt;

    @JsonProperty("kakao_account")
    private KakaoAccount kakaoAccount;

    // ── 편의 메서드 ──────────────────────────────────────────

    /** 이메일 — 동의 거부 또는 미검증 시 null 반환 */
    public String getEmail() {
        if (kakaoAccount == null || kakaoAccount.isEmailNeedsAgreement()) return null;
        if (!kakaoAccount.isEmailVerified()) return null;
        return kakaoAccount.getEmail();
    }

    /** 닉네임 — 동의 거부 시 null 반환 */
    public String getNickname() {
        if (kakaoAccount == null || kakaoAccount.getProfile() == null) return null;
        if (kakaoAccount.isProfileNicknameNeedsAgreement()) return null;
        return kakaoAccount.getProfile().getNickname();
    }

    /** 프로필 이미지 URL */
    public String getProfileImageUrl() {
        if (kakaoAccount == null || kakaoAccount.getProfile() == null) return null;
        return kakaoAccount.getProfile().getProfileImageUrl();
    }

    // ── 중첩 클래스 ──────────────────────────────────────────

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KakaoAccount {

        @JsonProperty("profile_nickname_needs_agreement")
        private boolean profileNicknameNeedsAgreement;

        @JsonProperty("email_needs_agreement")
        private boolean emailNeedsAgreement;

        @JsonProperty("is_email_valid")
        private boolean emailValid;

        @JsonProperty("is_email_verified")
        private boolean emailVerified;

        @JsonProperty("email")
        private String email;

        @JsonProperty("profile")
        private Profile profile;

        @Getter
        @NoArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Profile {

            @JsonProperty("nickname")
            private String nickname;

            @JsonProperty("profile_image_url")
            private String profileImageUrl;

            @JsonProperty("thumbnail_image_url")
            private String thumbnailImageUrl;

            @JsonProperty("is_default_image")
            private boolean defaultImage;
        }
    }
}
