package map.service.user.schedule;

/**
 * ScheduleReplanUnavailableException — 다시 짤 수 없는 일정에 재추천을 요청함
 *
 * 지역을 모르는 일정(지역 보존 이전에 저장된 행)은 어느 동네로 다시 짜야 할지
 * 알 수 없다. 좌표로 지역을 추측해 진행하면 엉뚱한 동네 코스가 나오므로,
 * 그냥 못 한다고 답하고 사용자가 새로 추천받게 둔다.
 *
 * 전역 예외 처리에서 409 로 옮긴다 — 요청 형식은 옳고(400 아님) 일정도
 * 존재하지만(404 아님) 지금 상태로는 수행할 수 없다는 뜻이다.
 */
public class ScheduleReplanUnavailableException extends RuntimeException {

    public ScheduleReplanUnavailableException(Long scheduleId) {
        super("schedule " + scheduleId + " has no region to replan with");
    }
}
