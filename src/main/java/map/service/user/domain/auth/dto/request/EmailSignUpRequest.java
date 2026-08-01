package map.service.user.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import map.service.user.domain.user.entity.DeviceType;

/**
 * EmailSignUpRequest — 이메일 회원가입 요청 본문
 *
 * 이메일·비밀번호·닉네임은 필수이고, 그 뒤의 개인 정보와 취향은 전부 선택
 * 항목이다. 가입 화면이 단계별로 받는데 뒤 단계를 건너뛸 수 있고, 다른 가입
 * 경로(소셜)는 이 단계를 아예 거치지 않기 때문이다.
 *
 * 관심사·테마의 **값 종류는 검증하지 않는다.** 목록이 화면에 있고 거기서
 * 늘거나 줄어드는데, 같은 목록을 서버에 복제해 두면 화면이 바뀔 때마다 서버도
 * 같이 고쳐야 하고 어긋나는 순간 가입이 막힌다. 여기서는 개수와 길이만 막아
 * 비대한 요청을 거른다.
 */
@Getter
@NoArgsConstructor
public class EmailSignUpRequest {

    /** 관심사 개수 상한. 화면이 제시하는 항목 수와 같게 둔다. */
    private static final int MAX_INTERESTS = 21;

    /** 테마 개수 상한. 화면이 제시하는 항목 수와 같게 둔다. */
    private static final int MAX_THEMES = 8;

    /** 목록 항목 하나의 길이 상한. */
    private static final int MAX_ITEM_LENGTH = 64;

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다.")
    @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하입니다.")
    private String password;

    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(min = 1, max = 50, message = "닉네임은 1자 이상 50자 이하입니다.")
    private String nickname;

    /** 생년월일. 오늘 이후 날짜는 받지 않는다. */
    @Past(message = "생년월일은 오늘 이전이어야 합니다.")
    private LocalDate birthDate;

    @Size(max = 16, message = "성별은 16자 이하입니다.")
    private String gender;

    @Size(max = MAX_INTERESTS, message = "관심사는 21개 이하입니다.")
    private List<
            @Size(max = MAX_ITEM_LENGTH, message = "관심사 항목은 64자 이하입니다.")
            String> interests;

    @Size(max = MAX_THEMES, message = "테마는 8개 이하입니다.")
    private List<
            @Size(max = MAX_ITEM_LENGTH, message = "테마 항목은 64자 이하입니다.")
            String> themes;

    private String     deviceToken;
    private DeviceType deviceType;
}
