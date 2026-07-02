package map.service.user.domain.auth.dto.response;

import lombok.Builder;
import lombok.Getter;
import map.service.user.domain.user.entity.AuthProvider;

@Getter
@Builder
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private long accessTokenExpiresIn;
    private long refreshTokenExpiresIn;
    private UserInfo user;

    @Getter
    @Builder
    public static class UserInfo {
        private Long id;
        private String email;
        private String nickname;
        private String profileImageUrl;
        private AuthProvider authProvider;
        private boolean emailVerified;
    }
}
