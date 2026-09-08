package map.service.user.domain.user.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * UserUpdateRequest — 내 정보 수정 요청 본문
 *
 * 모든 항목이 선택이다. 화면이 자기가 다루는 부분만 보내고, 보내지 않은
 * 항목은 그대로 둔다 — 프로필 편집 화면과 관심사 설정 화면이 따로 있어
 * 한쪽이 다른 쪽 값을 지우면 안 된다.
 *
 * 목록을 비우려면 빈 목록을 보낸다. null 은 "건드리지 않음"이다.
 *
 * 값 종류는 가입 요청과 같은 이유로 검증하지 않는다 — 목록은 화면이
 * 정하며, 서버에 복제해 두면 화면이 바뀔 때마다 어긋난다.
 */
public record UserUpdateRequest(
        @Size(max = 50, message = "닉네임은 50자 이하입니다.")
        String nickname,

        @Size(max = 500, message = "프로필 이미지 주소는 500자 이하입니다.")
        String profileImageUrl,

        LocalDate birthDate,

        @Size(max = 16, message = "성별은 16자 이하입니다.")
        String gender,

        @Size(max = 21, message = "관심사는 21개 이하입니다.")
        List<@Size(max = 64, message = "관심사 항목은 64자 이하입니다.") String> interests,

        @Size(max = 8, message = "테마는 8개 이하입니다.")
        List<@Size(max = 64, message = "테마 항목은 64자 이하입니다.") String> themes
) {
}
