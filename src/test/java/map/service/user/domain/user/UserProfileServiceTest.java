package map.service.user.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import map.service.user.domain.user.dto.UserMeResponse;
import map.service.user.domain.user.dto.UserUpdateRequest;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.domain.user.service.UserProfileService;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UserProfileServiceTest — 내 정보 조회·수정 단위 테스트
 *
 * 가장 중요한 계약은 **보내지 않은 항목을 지우지 않는 것**이다. 프로필 편집
 * 화면과 관심사 설정 화면이 따로 있어, 한쪽이 자기 항목만 보냈을 때 다른
 * 쪽 값이 사라지면 안 된다.
 */
@DisplayName("UserProfileService 내 정보 조회·수정")
class UserProfileServiceTest {

    private UserRepository userRepository;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserProfileService(userRepository);
    }

    private User user() {
        return User.builder()
                .email("a@b.c")
                .nickname("테스터")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .birthDate(LocalDate.of(1998, 3, 2))
                .gender("여성")
                .interests(List.of("맛집 🍜", "카페 ☕"))
                .themes(List.of("food"))
                .build();
    }

    @Test
    @DisplayName("가입 때 고른 값이 그대로 조회된다")
    void getMeReturnsStoredValues() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        UserMeResponse out = service.getMe(1L);

        assertThat(out.nickname()).isEqualTo("테스터");
        assertThat(out.birthDate()).isEqualTo(LocalDate.of(1998, 3, 2));
        assertThat(out.gender()).isEqualTo("여성");
        assertThat(out.interests()).containsExactly("맛집 🍜", "카페 ☕");
        assertThat(out.themes()).containsExactly("food");
    }

    @Test
    @DisplayName("값이 없는 목록은 빈 목록으로 나간다")
    void getMeNormalizesNullLists() {
        User bare = User.builder()
                .email("a@b.c").nickname("테스터")
                .authProvider(AuthProvider.EMAIL).emailVerified(false)
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(bare));

        UserMeResponse out = service.getMe(1L);

        assertThat(out.interests()).isEmpty();
        assertThat(out.themes()).isEmpty();
    }

    @Test
    @DisplayName("보내지 않은 항목은 그대로 둔다")
    void updateLeavesOmittedFieldsUntouched() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        // 관심사 설정 화면은 관심사만 보낸다.
        UserMeResponse out = service.updateMe(1L, new UserUpdateRequest(
                null, null, null, null, List.of("서점 📚"), null));

        assertThat(out.interests()).containsExactly("서점 📚");
        assertThat(out.nickname()).isEqualTo("테스터");
        assertThat(out.birthDate()).isEqualTo(LocalDate.of(1998, 3, 2));
        assertThat(out.gender()).isEqualTo("여성");
        assertThat(out.themes()).containsExactly("food");
    }

    @Test
    @DisplayName("빈 목록을 보내면 비운다")
    void updateWithEmptyListClearsIt() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        UserMeResponse out = service.updateMe(1L, new UserUpdateRequest(
                null, null, null, null, List.of(), null));

        assertThat(out.interests()).isEmpty();
    }

    @Test
    @DisplayName("프로필 편집이 생년월일·성별까지 반영한다")
    void updateAppliesProfileFields() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        UserMeResponse out = service.updateMe(1L, new UserUpdateRequest(
                "새이름", "http://x/img.png",
                LocalDate.of(2000, 1, 1), "남성", null, null));

        assertThat(out.nickname()).isEqualTo("새이름");
        assertThat(out.profileImageUrl()).isEqualTo("http://x/img.png");
        assertThat(out.birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(out.gender()).isEqualTo("남성");
    }

    @Test
    @DisplayName("토큰 없이 도달하면 인증 실패로 돌린다")
    void nullPrincipalIsRejected() {
        assertThatThrownBy(() -> service.getMe(null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("없는 사용자는 404 로 돌린다")
    void missingUserIsRejected() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMe(9L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
    }
}
