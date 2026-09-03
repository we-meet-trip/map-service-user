package map.service.user.schedule;

import java.time.LocalDate;

/**
 * ScheduleReplanSpec — 저장된 일정을 다시 짜는 데 필요한 조건 한 벌
 *
 * 일정 도메인이 소유자·지역·기간을 검증한 뒤 내주는 값이다. trip 도메인은 이
 * 사양만 받아 추천을 다시 돌린다 — 엔티티를 통째로 넘기면 trip 쪽에서 일정
 * 상태를 마음대로 바꿀 수 있게 되고, 두 도메인의 경계가 흐려진다.
 *
 * scheduleId: 다시 짤 일정 식별자. 새 추천 요청의 schedule_id 로도 쓴다.
 * province / city: 추천 당시 지역. 날씨와 장소 탐색의 기준.
 * dateStart / dateEnd: 여행 기간.
 * transport: 이동수단(walk|bicycle|scooter|bus). 저장돼 있지 않으면 null.
 * activeStartHour / activeEndHour: 하루 활동 시간대. 없으면 null(기본값 적용).
 */
public record ScheduleReplanSpec(
        Long scheduleId,
        String province,
        String city,
        LocalDate dateStart,
        LocalDate dateEnd,
        String transport,
        Integer activeStartHour,
        Integer activeEndHour
) {
}
