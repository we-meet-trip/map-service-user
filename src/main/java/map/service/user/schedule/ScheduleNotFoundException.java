package map.service.user.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * ScheduleNotFoundException — 일정 저장용 draft 미존재 예외
 *
 * ScheduleService.persist 에서 DraftStore.find 가 비어 있을 때 던진다.
 * @ResponseStatus(NOT_FOUND) 로 HTTP 404 가 자동 매핑된다.
 * 메시지는 "draft not found: {jobId}" 형식.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ScheduleNotFoundException extends RuntimeException {

    /**
     * jobId 로 예외를 생성.
     *
     * jobId: 찾지 못한 draft 의 작업 식별자.
     */
    public ScheduleNotFoundException(String jobId) {
        super("draft not found: " + jobId);
    }
}
