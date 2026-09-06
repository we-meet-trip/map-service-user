package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * RecommendRequest — 추천 요청 본문
 *
 * 클라이언트가 /api/v1/recommend 로 POST 할 때의 본문 형식.
 * RecommendController.create 와 .research 가 @RequestBody 로 받는다.
 *
 * date: 여행 기간/시간대. DateRange 중첩, @Valid @NotNull.
 * budget: 예산(KRW). nullable.
 * theme: 테마 키워드 리스트. nullable.
 * mobility: 이동수단(walk/bicycle/car/transit). nullable.
 * province: 광역시도. @NotBlank, 1~20자.
 * city: 시군구. @NotBlank, 1~20자.
 * scheduleId: user 일정 식별자 패스스루(B2 확장 계약). nullable.
 *             JSON key "schedule_id". agent 는 추적용으로만 사용한다.
 * stage: 추천 단계("init" | "mode1" | "route"). 클라이언트 입력은 신뢰하지
 *        않으며 RecommendService 가 엔드포인트별로 서버측에서 강제한다.
 * exclude: Mode 1 재탐색 시 재추천 금지 content_id 목록(최대 50개).
 *          stage 와 동일하게 서버측에서 재구성한다.
 * places: 사용자가 직접 고른 방문지 목록(2~10개). 경로 전용 엔드포인트에서만
 *         채워지며, 다른 경로로 들어오면 서버가 비워서 agent 에 넘긴다 —
 *         탐색 기반 추천에 장소 목록이 섞이면 agent 가 어느 쪽을 따를지
 *         모호해진다.
 */
public record RecommendRequest(
        @Valid @NotNull DateRange date,
        Integer budget,
        List<String> theme,
        Mobility mobility,
        @NotBlank @Size(min = 1, max = 20) String province,
        @NotBlank @Size(min = 1, max = 20) String city,
        @JsonProperty("schedule_id") @Size(max = 64) String scheduleId,
        @Size(max = 10) String stage,
        @Size(max = 50) List<@Size(max = 64) String> exclude,
        @Valid @Size(min = 2, max = 10) List<SelectedPlace> places,
        boolean optimize
) {
    public RecommendRequest(DateRange date, Integer budget, List<String> theme, Mobility mobility,
                            String province, String city, String scheduleId, String stage,
                            List<String> exclude, List<SelectedPlace> places) {
        this(date, budget, theme, mobility, province, city, scheduleId, stage, exclude, places, false);
    }
}
