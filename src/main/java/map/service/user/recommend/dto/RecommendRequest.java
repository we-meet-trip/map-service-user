package map.service.user.recommend.dto;

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
 */
public record RecommendRequest(
        @Valid @NotNull DateRange date,
        Integer budget,
        List<String> theme,
        Mobility mobility,
        @NotBlank @Size(min = 1, max = 20) String province,
        @NotBlank @Size(min = 1, max = 20) String city
) {
}
