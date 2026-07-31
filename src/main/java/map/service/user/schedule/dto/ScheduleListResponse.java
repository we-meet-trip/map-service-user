package map.service.user.schedule.dto;

import java.util.List;

/**
 * ScheduleListResponse — 일정 목록 응답 본문
 *
 * 최상위를 배열이 아닌 객체로 감싼다. 나중에 페이지 커서나 총 건수를 덧붙일
 * 때 client 의 파싱을 깨지 않고 키만 추가할 수 있다.
 *
 * schedules: 소유자의 일정 목록. 없으면 빈 배열(널 아님).
 */
public record ScheduleListResponse(List<ScheduleSummary> schedules) {
}
