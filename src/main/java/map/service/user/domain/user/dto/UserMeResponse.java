package map.service.user.domain.user.dto;

import java.time.LocalDate;
import java.util.List;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;

/**
 * UserMeResponse — 내 정보 조회·수정 응답 본문
 *
 * 마이페이지의 프로필 편집과 관심사 설정 화면이 그리는 값을 담는다.
 * 인증 응답의 사용자 정보보다 넓다 — 그쪽은 로그인 직후 화면에 필요한
 * 최소한만 담고, 여기는 편집 대상 전부를 담는다.
 *
 * 목록은 값이 없을 때 null 대신 빈 목록으로 내보낸다. 화면이 두 경우를
 * 갈라 다룰 이유가 없다.
 */
public record UserMeResponse(
        Long id,
        String email,
        String nickname,
        String profileImageUrl,
        AuthProvider authProvider,
        boolean emailVerified,
        LocalDate birthDate,
        String gender,
        List<String> interests,
        List<String> themes
) {
    public static UserMeResponse from(User user) {
        return new UserMeResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getAuthProvider(),
                user.isEmailVerified(),
                user.getBirthDate(),
                user.getGender(),
                user.getInterests() == null ? List.of() : user.getInterests(),
                user.getThemes() == null ? List.of() : user.getThemes());
    }
}
