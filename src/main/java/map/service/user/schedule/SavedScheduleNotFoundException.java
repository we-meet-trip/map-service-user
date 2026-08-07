package map.service.user.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * SavedScheduleNotFoundException — 저장된 일정 조회·삭제 실패 예외
 *
 * ScheduleService 의 조회·삭제가 대상 일정을 찾지 못했을 때 던진다. 실제로
 * 없는 경우와 남의 일정인 경우를 구분하지 않는다 — 구분해서 알려 주면
 * 식별자를 바꿔 가며 어떤 일정이 존재하는지 알아낼 수 있다.
 *
 * 저장 단계의 draft 미존재(ScheduleNotFoundException)와 분리한 이유는 두
 * 상황의 복구 방법이 다르기 때문이다. 저장 실패는 "추천을 다시 생성"이지만,
 * 조회 실패는 "목록에서 다시 고르기"다. 안내 문구가 섞이면 사용자가 엉뚱한
 * 행동을 하게 된다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SavedScheduleNotFoundException extends RuntimeException {

    /**
     * scheduleId 로 예외를 생성.
     *
     * scheduleId: 찾지 못한(또는 접근 권한이 없는) 일정 식별자.
     */
    public SavedScheduleNotFoundException(Long scheduleId) {
        super("schedule not found: " + scheduleId);
    }
}
