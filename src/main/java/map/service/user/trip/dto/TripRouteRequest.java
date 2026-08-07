package map.service.user.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import map.service.user.recommend.dto.SelectedPlace;

/**
 * TripRouteRequest — POST /api/v1/trip/route 의 요청 본문
 *
 * 사용자가 지도에서 방문지를 직접 고른 뒤 "이 장소들로 동선을 짜 달라"고
 * 보내는 요청. 장소를 이미 정했으므로 예산·테마처럼 후보를 고르는 데 쓰는
 * 조건은 받지 않는다.
 *
 * 응답은 POST /api/v1/trip/generate 와 같은 TripGenerateResponse 다 — 결과
 * 화면이 같으므로 client 가 렌더링 코드를 나눌 필요가 없다.
 *
 * schedule: 일정/활동 시간대. 방문 시각을 이 구간에 균등 배치한다.
 * transport: 이동수단(bicycle/scooter/walk/bus). 이동 카드 라벨과 도로 경로
 *            프로파일이 이 값으로 정해진다.
 * location: 지역(province/city). 날씨 조회에 쓴다.
 * places: 사용자가 고른 방문지 2~10개. 1개면 이을 구간이 없고, 너무 많으면
 *         동선을 짜는 쪽이 감당하지 못한다.
 */
public record TripRouteRequest(
        @Valid @NotNull Schedule schedule,
        @NotBlank String transport,
        @Valid @NotNull Location location,
        @Valid @NotNull @Size(min = 2, max = 10) List<SelectedPlace> places
) {
}
