package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 저장된 취향을 테마 코드로 바꾸는 규칙.
 *
 * <p>가장 중요한 계약은 "밖으로 나가는 값은 지원하는 8종뿐" 과 "무슨 일이
 * 있어도 예외를 던지지 않는다" 둘이다. 전자를 어기면 이상한 문자열이
 * 장소 검색어가 되어 후보가 전멸하고, 후자를 어기면 취향 하나 때문에
 * 여행 일정이 통째로 실패한다.
 */
class ProfileThemeProviderTest {

    private UserRepository userRepository;
    private ProfileThemeProvider provider;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        provider = new ProfileThemeProvider(userRepository);
    }

    private void givenProfile(List<String> interests, List<String> themes) {
        User user = mock(User.class);
        when(user.getInterests()).thenReturn(interests);
        when(user.getThemes()).thenReturn(themes);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    // ---- 조회 자체가 없거나 실패하는 경우 ----

    // userId 가 없으면 조회하지 않는다
    @Test
    void nullUserIdDoesNotTouchTheRepository() {
        assertThat(provider.themesFor(null)).isEmpty();
        verify(userRepository, never()).findById(anyLong());
    }

    // 계정이 지워졌어도 예외 대신 빈 목록이다
    @Test
    void missingUserYieldsEmptyList() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(provider.themesFor(1L)).isEmpty();
    }

    // 조회가 터져도 예외를 밖으로 내보내지 않는다
    @Test
    void repositoryFailureYieldsEmptyList() {
        when(userRepository.findById(1L))
                .thenThrow(new RuntimeException("db down"));
        assertThat(provider.themesFor(1L)).isEmpty();
    }

    // 두 컬럼이 모두 비어 있어도 안전하다
    @Test
    void nullColumnsYieldEmptyList() {
        givenProfile(null, null);
        assertThat(provider.themesFor(1L)).isEmpty();
    }

    // ---- 걸러내기 ----

    // 지원하지 않는 코드는 내보내지 않는다
    @Test
    void unsupportedThemeCodesAreDropped() {
        givenProfile(null, List.of("food", "산책", "x".repeat(64), ""));
        assertThat(provider.themesFor(1L)).containsExactly("food");
    }

    // 대소문자와 앞뒤 공백은 흡수한다
    @Test
    void themeCodesAreNormalized() {
        givenProfile(null, List.of("  FOOD ", "Cafe"));
        assertThat(provider.themesFor(1L)).containsExactly("food", "cafe");
    }

    // 목록 안의 null 원소가 섞여도 죽지 않는다
    @Test
    void nullElementsAreIgnored() {
        givenProfile(Arrays.asList("맛집 🍜", null),
                Arrays.asList(null, "cafe"));
        assertThat(provider.themesFor(1L)).containsExactly("cafe", "food");
    }

    // ---- 관심사 라벨 접기 ----

    // 이모지를 뗀 한글 부분으로 매핑한다
    @Test
    void interestLabelsFoldToThemeCodes() {
        givenProfile(List.of("맛집 🍜", "카페 ☕", "해변 🏖️"), null);
        assertThat(provider.themesFor(1L))
                .containsExactly("food", "cafe", "nature");
    }

    // 한글 안쪽에 공백이 있는 라벨도 온전히 매핑된다
    @Test
    void multiWordInterestLabelsStillMatch() {
        givenProfile(List.of("사진 명소 📸", "스포츠 관람 🏟️"), null);
        assertThat(provider.themesFor(1L))
                .containsExactly("photo", "activity");
    }

    // 이모지가 바뀌거나 빠져도 매핑이 유지된다
    @Test
    void mappingSurvivesEmojiChanges() {
        givenProfile(List.of("맛집", "카페 🥤"), null);
        assertThat(provider.themesFor(1L)).containsExactly("food", "cafe");
    }

    // 귀속이 정해지지 않은 라벨은 버린다
    @Test
    void undecidedInterestLabelsAreDropped() {
        givenProfile(List.of("골목 🏘️", "힐링 🧘", "당일치기 🚗"), null);
        assertThat(provider.themesFor(1L)).isEmpty();
    }

    // ---- 합치기 ----

    // 직접 고른 코드가 앞, 관심사에서 접은 것이 뒤다
    @Test
    void themesComeBeforeFoldedInterests() {
        givenProfile(List.of("맛집 🍜"), List.of("night"));
        assertThat(provider.themesFor(1L)).containsExactly("night", "food");
    }

    // 양쪽에서 같은 코드가 나와도 한 번만 담는다
    @Test
    void duplicatesAcrossColumnsAppearOnce() {
        givenProfile(List.of("맛집 🍜", "브런치 🥞"), List.of("food"));
        assertThat(provider.themesFor(1L)).containsExactly("food");
    }

    // ---- 장식 제거 규칙 ----

    // 뒤쪽 장식만 떼고 한글 사이 공백은 남긴다
    @Test
    void stripDecorationKeepsInnerSpaces() {
        assertThat(ProfileThemeProvider.stripDecoration("사진 명소 📸"))
                .isEqualTo("사진 명소");
        assertThat(ProfileThemeProvider.stripDecoration("역사 🏛️"))
                .isEqualTo("역사");
        assertThat(ProfileThemeProvider.stripDecoration("맛집"))
                .isEqualTo("맛집");
        assertThat(ProfileThemeProvider.stripDecoration(null)).isEmpty();
        assertThat(ProfileThemeProvider.stripDecoration("🚗")).isEmpty();
    }

    // 접기표의 값은 전부 지원 코드 안에 있다
    @Test
    void everyMappedValueIsSupported() {
        assertThat(ProfileThemeProvider.SUPPORTED)
                .containsAll(ProfileThemeProvider.INTEREST_TO_THEME.values());
    }

    // ---- 성향 스냅샷 ----

    /**
     * 스냅샷은 학습의 입력이 된다. 원문을 한 벌 더 두지 않으면서도 "누가
     * 물었는가" 를 남기는 것이 목적이라, 뭉개는 규칙 자체가 계약이다.
     */
    private void givenProfile(java.time.LocalDate birth, String gender,
                              List<String> themes) {
        // 사용자를 먼저 다 만든 뒤에 저장소를 스텁한다. 스텁 안에서 또 스텁하면
        // Mockito 가 끝나지 않은 스텁으로 보고 거절한다.
        User user = mock(User.class);
        when(user.getBirthDate()).thenReturn(birth);
        when(user.getGender()).thenReturn(gender);
        when(user.getThemes()).thenReturn(themes);
        when(user.getInterests()).thenReturn(null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
    }

    @Test
    void segmentBucketsAgeAndCodesGender() {
        givenProfile(java.time.LocalDate.now().minusYears(27), "남성",
                List.of("food", "cafe"));

        var segment = provider.segmentFor(1L, false);

        assertThat(segment).containsEntry("age_band", "20s");
        assertThat(segment).containsEntry("gender", "m");
        assertThat(segment).containsEntry("themes", List.of("food", "cafe"));
        assertThat(segment).containsEntry("theme_merged", false);
    }

    @Test
    void segmentKeepsUnknownAsAValueNotAMissingKey() {
        // 빼 버리면 읽는 쪽이 "없음" 과 "안 물어봄" 을 구분하지 못한다.
        givenProfile(null, null, null);

        var segment = provider.segmentFor(1L, true);

        assertThat(segment).containsEntry("age_band", "unknown");
        assertThat(segment).containsKey("gender");
        assertThat(segment.get("gender")).isNull();
        assertThat(segment).containsEntry("themes", List.of());
        assertThat(segment).containsEntry("theme_merged", true);
    }

    @Test
    void segmentDropsUnsupportedThemes() {
        // 스냅샷에도 8종 밖의 값이 들어가면 안 된다. 학습에서 갈래가 늘어난다.
        givenProfile(null, null, Arrays.asList("food", "당일치기 🚗", null));

        assertThat(provider.segmentFor(1L, false))
                .containsEntry("themes", List.of("food"));
    }

    @Test
    void segmentIsNullWhenUserIsUnknownOrLookupFails() {
        assertThat(provider.segmentFor(null, false)).isNull();

        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThat(provider.segmentFor(1L, false)).isNull();

        when(userRepository.findById(1L)).thenThrow(new RuntimeException("db down"));
        assertThat(provider.segmentFor(1L, false)).isNull();
    }

    @Test
    void ageBandFoldsExtremesIntoOneBucket() {
        // 잘못 입력된 생년월일이 한 칸을 혼자 차지하면 그 칸은 사실상 개인이다.
        assertThat(ProfileThemeProvider.ageBand(
                java.time.LocalDate.now().minusYears(85))).isEqualTo("70plus");
        assertThat(ProfileThemeProvider.ageBand(
                java.time.LocalDate.now().minusYears(200))).isEqualTo("unknown");
        assertThat(ProfileThemeProvider.ageBand(
                java.time.LocalDate.now().plusYears(5))).isEqualTo("unknown");
    }

    @Test
    void genderCodeAcceptsScreenSpellingsAndRejectsTheRest() {
        // 화면 표기가 바뀌어도 학습 평면의 값이 갈라지지 않게 여기서 맞춘다.
        assertThat(ProfileThemeProvider.genderCode("남성")).isEqualTo("m");
        assertThat(ProfileThemeProvider.genderCode("여성")).isEqualTo("f");
        assertThat(ProfileThemeProvider.genderCode("Male")).isEqualTo("m");
        assertThat(ProfileThemeProvider.genderCode("기타")).isNull();
        assertThat(ProfileThemeProvider.genderCode(null)).isNull();
    }
}
