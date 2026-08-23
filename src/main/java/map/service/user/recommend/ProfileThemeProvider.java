package map.service.user.recommend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 저장해 둔 취향을 여행 테마 코드로 바꿔 준다.
 *
 * <p>마이페이지에서 받는 값은 두 가지다. themes 는 여행 계획 화면과 같은
 * 8종 코드로 저장되고, interests 는 '맛집 🍜' 처럼 화면에 보이던 라벨
 * 원문이 그대로 들어간다. 둘 다 DB 에 값 제약이 없어 무엇이든 들어올 수
 * 있으므로, 밖으로 내보내기 전에 8종 코드만 남긴다.
 *
 * <p>화이트리스트로 거르는 이유가 뚜렷하다. 추천 서비스는 테마를 장소
 * 검색어로 그대로 쓰고, 매핑에 없는 값은 원문을 검색어로 삼는다. 즉
 * 걸러내지 않으면 '당일치기 🚗' 같은 문자열이 장소 검색어가 되어 결과가
 * 0건이 되고, 그 한 건의 실패가 후보 전체를 무너뜨린다.
 *
 * <p><b>이 클래스는 예외를 던지지 않는다.</b> 취향은 추천에 얹는 덤이라,
 * 조회가 실패했다고 여행 일정 자체가 실패하면 안 된다. 사용자 미존재·DB
 * 장애·이상한 값은 전부 "취향 없음"으로 접는다.
 */
@Slf4j
@Component
public class ProfileThemeProvider {

    /** 여행 계획 화면이 쓰는 테마 코드. 이 집합 밖은 내보내지 않는다. */
    static final Set<String> SUPPORTED = Set.of(
            "food", "photo", "nature", "cafe",
            "history", "activity", "shopping", "night");

    /**
     * 관심사 라벨 → 테마 코드.
     *
     * <p>키는 라벨에서 이모지를 떼어 낸 한글 부분이다. 라벨 전체를 키로
     * 잡으면 화면에서 이모지를 하나 바꾸는 순간 매칭이 조용히 전멸한다 —
     * 표기만 바꾼 배포인데 취향 반영이 사라지고 아무 오류도 나지 않는다.
     *
     * <p>여기 없는 라벨은 버린다. 근거 없이 아무 테마에나 붙이면 취향
     * 반영이 아니라 추천을 엉뚱한 쪽으로 끄는 일이 된다. 어디에 붙일지
     * 판단이 갈리는 라벨(골목·힐링·테마파크·전시·축제·공연·서점·오락)은
     * 정해질 때까지 넣지 않는다.
     */
    static final Map<String, String> INTEREST_TO_THEME = Map.ofEntries(
            Map.entry("맛집", "food"),
            Map.entry("브런치", "food"),
            Map.entry("카페", "cafe"),
            Map.entry("자연", "nature"),
            Map.entry("공원", "nature"),
            Map.entry("해변", "nature"),
            Map.entry("역사", "history"),
            Map.entry("문화", "history"),
            Map.entry("쇼핑", "shopping"),
            Map.entry("마켓", "shopping"),
            Map.entry("사진 명소", "photo"),
            Map.entry("스포츠 관람", "activity"));

    private final UserRepository userRepository;

    public ProfileThemeProvider(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 사용자의 취향을 테마 코드 목록으로 돌려준다.
     *
     * <p>themes 를 먼저, interests 에서 접은 것을 뒤에 둔다. themes 는
     * 사용자가 같은 어휘로 직접 고른 값이라 더 분명한 신호다.
     *
     * @param userId 인증된 사용자 식별자. null 이면 조회하지 않는다.
     * @return 8종 코드만 담긴 목록(중복 없음, 순서 보존). 없으면 빈 목록.
     */
    public List<String> themesFor(Long userId) {
        if (userId == null) {
            return List.of();
        }
        Optional<User> found;
        try {
            found = userRepository.findById(userId);
        } catch (RuntimeException e) {
            // 취향을 못 읽었다고 추천을 실패시키지 않는다. 재사용 캐시
            // 조회 실패를 미스로 접는 것과 같은 규약이다.
            log.warn("profile theme lookup failed userId={} err={}",
                    userId, e.getClass().getSimpleName());
            return List.of();
        }
        if (found.isEmpty()) {
            return List.of();
        }
        User user = found.get();
        Set<String> out = new LinkedHashSet<>();
        addThemeCodes(out, user.getThemes());
        addInterestCodes(out, user.getInterests());
        return List.copyOf(out);
    }

    /**
     * 잡에 붙일 성향 스냅샷을 만든다. 모르면 null.
     *
     * <p>학습의 입력에 "누가 물었는가" 를 넣기 위한 값이다. 조인으로 그때그때
     * 읽지 않고 스냅샷을 두는 이유는, 사람이 프로필을 고치면 과거 잡의 입력까지
     * 함께 바뀌어 같은 기록이 읽을 때마다 달라지기 때문이다.
     *
     * <p>나이는 10년 단위로 뭉개고 성별은 코드로 바꾼다. 원문을 학습 평면에 한
     * 벌 더 두지 않으려는 것이다. 값을 모르는 칸은 {@code unknown}·null 로 남겨
     * "모른다" 를 1급으로 다룬다 — 빼 버리면 읽는 쪽이 "없음" 과 "안 물어봄" 을
     * 구분하지 못한다.
     *
     * <p>themeMerged 는 그 요청의 테마에 저장된 취향이 섞였는지다. 섞인 요청은
     * 입력에 이미 이 사람의 성향이 들어가 있어, 성향을 따로 쓰는 학습에서 같은
     * 정보를 두 번 세게 된다. 갈라 보려면 표시가 있어야 한다.
     *
     * <p>이 클래스의 규약대로 예외를 던지지 않는다. 못 읽으면 null 이고,
     * 부르는 쪽은 성향 없이 기록한다.
     *
     * @return 스냅샷 맵. 사용자를 모르거나 조회에 실패하면 null.
     */
    public Map<String, Object> segmentFor(Long userId, boolean themeMerged) {
        if (userId == null) {
            return null;
        }
        Optional<User> found;
        try {
            found = userRepository.findById(userId);
        } catch (RuntimeException e) {
            log.warn("profile segment lookup failed userId={} err={}",
                    userId, e.getClass().getSimpleName());
            return null;
        }
        if (found.isEmpty()) {
            return null;
        }
        User user = found.get();
        Set<String> themes = new LinkedHashSet<>();
        addThemeCodes(themes, user.getThemes());
        addInterestCodes(themes, user.getInterests());

        // 값을 모르는 칸도 키는 남긴다. 빼 버리면 읽는 쪽이 "없음" 과
        // "안 물어봄" 을 구분하지 못한다.
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("age_band", ageBand(user.getBirthDate()));
        segment.put("gender", genderCode(user.getGender()));
        segment.put("themes", List.copyOf(themes));
        segment.put("theme_merged", themeMerged);
        return segment;
    }

    /**
     * 생년월일을 10년 단위 묶음으로 바꾼다. 모르면 {@code unknown}.
     *
     * <p>상한을 두는 이유: 잘못 입력된 생년월일(1900년 등)이 그대로 통계의
     * 한 칸을 차지하면 그 칸에 사람이 하나뿐이라 사실상 개인 식별이 된다.
     * 70대 이상은 한 칸으로 묶는다.
     */
    static String ageBand(java.time.LocalDate birthDate) {
        if (birthDate == null) {
            return "unknown";
        }
        int age = java.time.Period.between(
                birthDate, java.time.LocalDate.now(
                        java.time.ZoneId.of("Asia/Seoul"))).getYears();
        if (age < 0 || age > 120) {
            return "unknown";
        }
        if (age < 10) {
            return "under10";
        }
        if (age >= 70) {
            return "70plus";
        }
        return (age / 10 * 10) + "s";
    }

    /**
     * 화면이 보낸 성별 표기를 코드로 바꾼다. 못 알아보면 null.
     *
     * <p>DB 에 값 제약이 없어 화면이 보내는 표기가 그대로 담긴다(실측: 남성·여성).
     * 표기가 바뀌어도 학습 평면의 값이 갈라지지 않도록 여기서 한 번 맞춘다.
     */
    static String genderCode(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("남성") || v.equals("남") || v.equals("m") || v.equals("male")) {
            return "m";
        }
        if (v.equals("여성") || v.equals("여") || v.equals("f") || v.equals("female")) {
            return "f";
        }
        return null;
    }

    /** 이미 코드로 저장된 값 — 지원 목록에 있는 것만 통과시킨다. */
    private static void addThemeCodes(Set<String> out, List<String> themes) {
        if (themes == null) {
            return;
        }
        for (String raw : themes) {
            String code = normalize(raw);
            if (code != null && SUPPORTED.contains(code)) {
                out.add(code);
            }
        }
    }

    /** 라벨로 저장된 값 — 이모지를 떼고 매핑표에서 찾는다. */
    private static void addInterestCodes(Set<String> out, List<String> labels) {
        if (labels == null) {
            return;
        }
        for (String raw : labels) {
            String code = INTEREST_TO_THEME.get(stripDecoration(raw));
            if (code != null) {
                out.add(code);
            }
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 라벨에서 뒤에 붙은 장식(이모지·이형 선택자·공백)을 떼어 낸다.
     *
     * <p>'사진 명소 📸' 처럼 한글 안쪽에도 공백이 있으므로 첫 공백에서
     * 자르면 안 된다. 뒤에서부터 한글·숫자·영문이 나올 때까지만 걷어낸다.
     */
    static String stripDecoration(String raw) {
        if (raw == null) {
            return "";
        }
        List<Integer> kept = new ArrayList<>();
        raw.codePoints().forEach(kept::add);
        int end = kept.size();
        while (end > 0 && !isMeaningful(kept.get(end - 1))) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            sb.appendCodePoint(kept.get(i));
        }
        return sb.toString().trim();
    }

    private static boolean isMeaningful(int codePoint) {
        return Character.isLetterOrDigit(codePoint);
    }
}
