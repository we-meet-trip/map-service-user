package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import map.service.user.recommend.dto.SelectedPlace;

/**
 * TripResearchRequest — POST /api/v1/trip/research 의 요청 본문
 *
 * "이 추천 말고 다른 곳"을 요구하는 재탐색 요청. 조건(일정·예산·테마·이동
 * 수단·지역)은 처음 추천과 같은 것을 그대로 다시 보낸다 — 조건을 바꾸는
 * 것이 아니라 같은 조건에서 다른 장소를 받는 흐름이라, 사용자에게 마법사를
 * 다시 태우지 않기 위해서다.
 *
 * 응답은 generate/route 와 같은 TripGenerateResponse 다.
 *
 * schedule/budget/themes/transport/location: generate 와 같은 의미·같은 형태.
 * prevTripId: 방금 받은 추천의 trip_id(=job_id). JSON key "prev_trip_id".
 *             재탐색 한도를 세는 열쇠이자, 서버가 그 추천의 장소를 스스로
 *             찾아 제외 목록에 더하는 근거다.
 * exclude: 다시 나오면 안 되는 장소 식별자 목록. 화면이 들고 있는 값을
 *          그대로 실어 보낸다. 서버가 이전 추천에서 모으는 목록과 합쳐진다 —
 *          그 추천이 이미 지워졌을 수 있어(만료·일정 저장) 화면 쪽 목록이
 *          더 오래 남는다. 상한 50개는 받는 쪽 계약과 같다.
 * keep: 마음에 들어 그대로 두려는 장소들. 이 장소는 다시 뽑지 않고 그 자리에
 *       고정하며, 나머지 자리만 새로 채운다. 비어 있으면 전부 다시 뽑는다.
 *       상한이 9인 이유는 한 곳은 새로 와야 재탐색이기 때문이다.
 *       각 항목의 content_id 가 있어야 고정한 장소를 제외 목록과 맞대 볼 수
 *       있다(없으면 같은 곳이 두 번 들어갈 수 있다).
 * scheduleId: 저장된 일정을 재탐색하는 경우의 일정 식별자. JSON key
 *             "schedule_id". 있으면 한도를 일정 단위로 센다.
 */
public record TripResearchRequest(
        @Valid @NotNull Schedule schedule,
        @Valid @NotNull BudgetRange budget,
        @NotEmpty List<String> themes,
        @NotBlank String transport,
        @Valid @NotNull Location location,
        @JsonProperty("prev_trip_id")
        @NotBlank
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                        + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "prev_trip_id must be a UUID")
        String prevTripId,
        @Size(max = 50) List<@Size(max = 64) String> exclude,
        @Valid @Size(max = 9) List<SelectedPlace> keep,
        @JsonProperty("schedule_id") Long scheduleId
) {
}
