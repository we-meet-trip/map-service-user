package map.service.user.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * InvalidScheduleDateException — 일정 날짜 범위 오류 예외
 *
 * ScheduleService.persist 에서 보정 후 dateStart 가 dateEnd 보다 뒤일 때 던진다.
 * @ResponseStatus(BAD_REQUEST) 로 HTTP 400 이 자동 매핑된다(클라이언트 입력 오류).
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidScheduleDateException extends RuntimeException {

    /**
     * 메시지로 예외를 생성.
     *
     * message: 사용자에게 전달할 검증 실패 사유.
     */
    public InvalidScheduleDateException(String message) {
        super(message);
    }
}
