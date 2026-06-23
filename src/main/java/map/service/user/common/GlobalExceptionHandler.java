package map.service.user.common;

import java.util.LinkedHashMap;
import java.util.Map;
import map.service.user.recommend.AgentRequestException;
import map.service.user.trip.TripGenerationException;
import map.service.user.trip.TripTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * GlobalExceptionHandler — agent 호출 관련 예외를 표준 응답으로 변환하는 전역 핸들러
 *
 * BFF 가 agent 를 호출하는 과정에서 발생하는 두 종류의 예외를 가로채
 * 클라이언트에게 일관된 JSON 응답으로 반환한다.
 *
 * 매핑:
 * - AgentRequestException   → HTTP 502 Bad Gateway
 *   (upstream 의 비정상 상태/본문을 BFF 가 클라이언트에 노출하지 않고 요약)
 * - ResourceAccessException → HTTP 504 Gateway Timeout
 *   (agent 까지 도달하지 못하거나 응답이 타임아웃된 경우)
 *
 * 응답 본문 포맷 (LinkedHashMap 으로 키 순서 보존):
 * - error           : 사람이 읽기 좋은 분류 문자열
 * - upstream_status : (502 한정) agent 가 반환한 원본 HTTP 상태 코드
 * - detail          : 클라이언트에 노출되는 추가 정보. 본문은 100자까지 잘라 노출.
 *
 * 사용처:
 * - RestControllerAdvice 로 등록되어 모든 컨트롤러에 자동 적용된다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 핸들러 내부 로깅용 로거. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /** 클라이언트에 노출하는 upstream 본문 최대 길이(문자 수). */
    private static final int CLIENT_BODY_MAX = 100;
    /** 서버 로그에 남기는 upstream 본문 최대 길이(문자 수). 과대 응답 로그 폭주 방지. */
    private static final int LOG_BODY_MAX = 1000;

    /**
     * agent 가 비정상 응답을 반환했을 때 호출된다.
     *
     * - upstream 의 상태 코드와 본문을 WARN 로그로 남긴다.
     * - 클라이언트에는 502 Bad Gateway 와 함께 요약 정보만 전달한다.
     * - 본문은 truncatedBody(CLIENT_BODY_MAX) 로 잘라서 노출한다.
     *
     * @param ex  agent 호출 결과로 발생한 예외
     * @return    502 응답 ResponseEntity
     */
    @ExceptionHandler(AgentRequestException.class)
    public ResponseEntity<Map<String, Object>> handleAgentRequest(
            AgentRequestException ex
    ) {
        log.warn("agent upstream error status={} body={}",
                ex.statusCode(), ex.truncatedBody(LOG_BODY_MAX));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "agent upstream error");
        body.put("upstream_status", ex.statusCode());
        body.put("detail", ex.truncatedBody(CLIENT_BODY_MAX));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * agent 에 접근하지 못하거나 응답이 타임아웃된 경우 호출된다.
     *
     * - 예외 메시지(내부 호스트/URL 포함 가능)는 WARN 로그에만 남긴다.
     * - 클라이언트에는 504 Gateway Timeout + 고정 detail("upstream request
     *   timed out") 만 반환한다(인프라 정보 누출 차단).
     *
     * @param ex  RestClient 가 던진 ResourceAccessException
     * @return    504 응답 ResponseEntity
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(
            ResourceAccessException ex
    ) {
        // 원인 메시지(예: "I/O error on POST request for http://agent:8000...")는
        // 내부 호스트/URL 을 노출하므로 서버 로그에만 남기고, 클라이언트에는
        // 고정 문구만 반환한다(정보 누출 차단).
        log.warn("agent unreachable: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "agent unreachable");
        body.put("detail", "upstream request timed out");
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    /**
     * trip 추천 잡이 실패(status=failed)했거나 결과가 비정상일 때 호출된다.
     *
     * client(TripApiException)는 {error, message} 만 읽으므로 두 키를 채워 502 로 반환한다.
     *
     * @param ex  TripService 가 던진 추천 실패 예외
     * @return    502 응답 ResponseEntity
     */
    @ExceptionHandler(TripGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleTripGeneration(
            TripGenerationException ex
    ) {
        log.warn("trip generation failed: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "trip_generation_failed");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * trip 동기 facade 가 폴링 한도 내에 추천 결과를 받지 못했을 때 호출된다.
     *
     * 내부 식별자(job_id)는 로그에만 남기고, client 에는 고정 안내 문구만 반환한다.
     *
     * @param ex  TripService 가 던진 타임아웃 예외
     * @return    504 응답 ResponseEntity
     */
    @ExceptionHandler(TripTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTripTimeout(
            TripTimeoutException ex
    ) {
        log.warn("trip generation timeout: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "trip_generation_timeout");
        body.put("message", "추천 생성이 시간 내에 완료되지 않았습니다. 잠시 후 다시 시도해 주세요.");
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    /**
     * @Valid 본문 검증 실패 시 호출된다(필드 누락/형식 위반).
     *
     * 첫 번째 필드 오류를 message 로 노출하여 client 가 사용자에게 보여줄 수 있게 한다.
     *
     * @param ex  검증 예외
     * @return    400 응답 ResponseEntity
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex
    ) {
        org.springframework.validation.FieldError fieldError =
                ex.getBindingResult().getFieldError();
        String detail = fieldError != null
                ? fieldError.getField() + " " + fieldError.getDefaultMessage()
                : "요청 형식이 올바르지 않습니다.";
        log.warn("trip request invalid: {}", detail);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_request");
        body.put("message", detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 매핑 불가 입력(예: 지원하지 않는 transport) 시 호출된다.
     *
     * @param ex  IllegalArgumentException(TripMapping 등)
     * @return    400 응답 ResponseEntity
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex
    ) {
        log.warn("trip request rejected: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_request");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
