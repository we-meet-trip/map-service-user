package map.service.user.schedule;

/**
 * ScheduleReplanUnavailableException — 다시 짤 수 없는 일정에 재추천을 요청함
 *
 * 두 경우다. 하나는 지역을 모르는 일정(지역 보존 이전에 저장된 행) — 어느
 * 동네로 다시 짜야 할지 알 수 없고, 좌표로 추측하면 엉뚱한 동네 코스가 나온다.
 * 다른 하나는 이미 지나간 일정 — 다녀왔거나 가지 않기로 한 여행이라 다시 짤
 * 이유가 없고, 지나간 기록을 새 코스로 덮는 편이 더 나쁘다.
 *
 * 전역 예외 처리에서 409 로 옮긴다 — 요청 형식은 옳고(400 아님) 일정도
 * 존재하지만(404 아님) 지금 상태로는 수행할 수 없다는 뜻이다.
 */
public class ScheduleReplanUnavailableException extends RuntimeException {

    public ScheduleReplanUnavailableException(Long scheduleId, String reason) {
        super("schedule " + scheduleId + " cannot be replanned: " + reason);
    }
}
