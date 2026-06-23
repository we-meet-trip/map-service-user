package map.service.user.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * TripGenerateRequest — POST /api/v1/trip/generate 의 요청 본문
 *
 * Flutter client 의 6-step 마법사가 마지막 step 에서 전송하는 동기 요청.
 * 본 BFF 가 받아 agent RecommendRequest 로 변환(TripMapping)한 뒤 동기 facade 로
 * 추천을 구동한다. JSON 구조는 client 의 toJson 과 정확히 1:1 이다.
 *
 * schedule: 일정/활동 시간대. @Valid @NotNull.
 * budget:   예산 구간(min/max). @Valid @NotNull.
 * themes:   테마 키워드 리스트(food/photo/nature/cafe/history/activity/shopping/night).
 *           복수 선택. 비어 있지 않아야 한다.
 * transport: 이동수단(bicycle/scooter/walk/bus). @NotBlank.
 * location: 지역(province/city). @Valid @NotNull.
 */
public record TripGenerateRequest(
        @Valid @NotNull Schedule schedule,
        @Valid @NotNull BudgetRange budget,
        @NotEmpty List<String> themes,
        @NotBlank String transport,
        @Valid @NotNull Location location
) {
}
