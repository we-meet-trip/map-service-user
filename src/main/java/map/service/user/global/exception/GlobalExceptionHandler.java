package map.service.user.global.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import map.service.user.places.PlacePhotosException;
import map.service.user.places.PlaceSearchException;
import map.service.user.places.ReviewSearchException;
import map.service.user.recommend.AgentRequestException;
import map.service.user.trip.TripGenerationException;
import map.service.user.trip.TripTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — 전역 예외를 표준 응답으로 변환하는 단일 진입점
 *
 * 두 부류의 예외를 일관되게 처리한다.
 * - 인증/입력 계열: 구조화된 ErrorResponse(timestamp/status/code/message) 로 반환한다.
 * - 업스트림 프록시 계열(agent/hub 호출, trip facade): 호출 측이 읽는 키 구조를
 *   보존하기 위해 기존 LinkedHashMap 본문 형식을 그대로 유지한다.
 *
 * 응답 형식이 두 갈래인 이유:
 * - 인증 도메인은 code 기반 구조화 응답을 사용한다.
 * - 업스트림 오류는 호출 측 계약(error/upstream_status/detail, error/message)을 따른다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 클라이언트에 노출하는 upstream 본문 최대 길이(문자 수). */
    private static final int CLIENT_BODY_MAX = 100;
    /** 서버 로그에 남기는 upstream 본문 최대 길이(문자 수). 과대 응답 로그 폭주 방지. */
    private static final int LOG_BODY_MAX = 1000;

    // ── 인증/입력 계열 : 구조화 ErrorResponse ──────────────────────────────────

    /** 비즈니스 예외(CustomException)를 ErrorCode 의 상태/코드로 변환한다. */
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
        log.warn("CustomException: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ErrorResponse.of(e.getErrorCode()));
    }

    /** @Valid 본문 검증 실패. 필드 메시지를 모아 400 으로 반환한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "잘못된 입력값입니다.")
                .collect(Collectors.joining(", "));

        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .message(message)
                        .build()
        );
    }

    /** 본문 파싱 실패(형식 오류) 시 400 으로 반환한다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .message("잘못된 요청 형식입니다.")
                        .build()
        );
    }

    /** 필수 요청 헤더 누락 시 400 으로 반환한다. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .message("필수 헤더가 누락되었습니다: " + e.getHeaderName())
                        .build()
        );
    }

    /** 필수 요청 파라미터(@RequestParam) 누락 시 400 으로 반환한다(헤더 누락 처리와 대칭). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(400)
                        .code("VALIDATION_ERROR")
                        .message("필수 파라미터가 누락되었습니다: " + e.getParameterName())
                        .build()
        );
    }

    /** 지원하지 않는 HTTP 메서드 요청 시 405 로 반환한다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(405)
                        .code("METHOD_NOT_ALLOWED")
                        .message("지원하지 않는 HTTP 메서드입니다: " + e.getMethod())
                        .build()
        );
    }

    /**
     * 매핑되지 않은 경로. 정적 자원 탐색까지 실패하면 여기로 온다.
     *
     * 전용 처리가 없으면 최종 폴백이 잡아 500 으로 나가서, 주소를 잘못 부른
     * 쪽의 문제가 서버 장애처럼 보인다. 재시도해도 소용없음을 상태로 알린다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        log.warn("no handler for path: {}", e.getResourcePath());
        return ResponseEntity.status(404).body(
                ErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(404)
                        .code("NOT_FOUND")
                        .message("요청한 경로를 찾을 수 없습니다.")
                        .build()
        );
    }

    // ── 업스트림 프록시 계열 : 호출 측 계약(Map 본문) 유지 ──────────────────────

    /**
     * agent 가 비정상 응답을 반환했을 때 호출된다.
     * 상태/본문은 로그에 남기고, 클라이언트에는 502 와 요약 정보만 전달한다.
     */
    @ExceptionHandler(AgentRequestException.class)
    public ResponseEntity<Map<String, Object>> handleAgentRequest(AgentRequestException ex) {
        log.warn("agent upstream error status={} body={}",
                ex.statusCode(), ex.truncatedBody(LOG_BODY_MAX));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "agent upstream error");
        body.put("upstream_status", ex.statusCode());
        body.put("detail", ex.truncatedBody(CLIENT_BODY_MAX));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * hub 장소 조회가 비정상 응답을 반환했을 때 호출된다.
     * 상태/본문은 로그에 남기고, 클라이언트에는 502 와 요약 정보만 전달한다.
     */
    @ExceptionHandler(PlaceSearchException.class)
    public ResponseEntity<Map<String, Object>> handlePlaceSearch(PlaceSearchException ex) {
        log.warn("place search upstream error status={} body={}",
                ex.statusCode(), ex.truncatedBody(LOG_BODY_MAX));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "place_search_upstream_error");
        body.put("upstream_status", ex.statusCode());
        body.put("detail", ex.truncatedBody(CLIENT_BODY_MAX));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * hub 리뷰 조회가 비정상 응답을 반환했을 때 호출된다.
     * 상태/본문은 로그에 남기고, 클라이언트에는 502 와 요약 정보만 전달한다.
     */
    @ExceptionHandler(ReviewSearchException.class)
    public ResponseEntity<Map<String, Object>> handleReviewSearch(ReviewSearchException ex) {
        log.warn("review search upstream error status={} body={}",
                ex.statusCode(), ex.truncatedBody(LOG_BODY_MAX));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "review_search_upstream_error");
        body.put("upstream_status", ex.statusCode());
        body.put("detail", ex.truncatedBody(CLIENT_BODY_MAX));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * hub 장소 사진 조회가 비정상 응답을 반환했을 때 호출된다.
     * 상태/본문은 로그에 남기고, 클라이언트에는 502 와 요약 정보만 전달한다.
     */
    @ExceptionHandler(PlacePhotosException.class)
    public ResponseEntity<Map<String, Object>> handlePlacePhotos(PlacePhotosException ex) {
        log.warn("place photos upstream error status={} body={}",
                ex.statusCode(), ex.truncatedBody(LOG_BODY_MAX));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "place_photos_upstream_error");
        body.put("upstream_status", ex.statusCode());
        body.put("detail", ex.truncatedBody(CLIENT_BODY_MAX));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * @RequestParam 제약(@NotBlank/@Min/@Max 등) 위반 시 400 으로 변환한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("request param invalid: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_request");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * agent/hub 호출이 도달하지 못하거나 응답이 타임아웃된 경우 호출된다.
     * 내부 호스트/URL 이 담긴 원인 메시지는 로그에만 남기고, 클라이언트에는
     * 고정 문구만 반환한다(정보 누출 차단). 응답 본문은 기존 계약을 그대로 보존한다.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(ResourceAccessException ex) {
        log.warn("agent unreachable: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "agent unreachable");
        body.put("detail", "upstream request timed out");
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    /**
     * 현재 날씨를 만들 수 없을 때 호출된다.
     *
     * 카드의 본체인 지금 기온이 없으면 그릴 것이 없어 빈 값 응답 대신 오류로
     * 알린다. 원인은 로그에만 남기고 클라이언트에는 고정 안내만 준다.
     */
    @ExceptionHandler(map.service.user.weather.WeatherUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleWeatherUnavailable(
            map.service.user.weather.WeatherUnavailableException ex) {
        log.warn("weather home unavailable: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "weather_unavailable");
        body.put("message", "날씨 정보를 가져오지 못했어요.");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * trip 추천 잡이 실패(status=failed)했거나 결과가 비정상일 때 호출된다.
     * 호출 측이 {error, message} 를 읽으므로 두 키를 채워 502 로 반환한다.
     */
    @ExceptionHandler(TripGenerationException.class)
    public ResponseEntity<Map<String, Object>> handleTripGeneration(TripGenerationException ex) {
        log.warn("trip generation failed: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "trip_generation_failed");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * trip 동기 facade 가 폴링 한도 내에 추천 결과를 받지 못했을 때 호출된다.
     * 내부 식별자는 로그에만 남기고, 호출 측에는 고정 안내 문구만 반환한다.
     */
    @ExceptionHandler(TripTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTripTimeout(TripTimeoutException ex) {
        log.warn("trip generation timeout: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "trip_generation_timeout");
        body.put("message", "추천 생성이 시간 내에 완료되지 않았습니다. 잠시 후 다시 시도해 주세요.");
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    /**
     * 매핑 불가 입력(예: 지원하지 않는 transport) 시 400 으로 반환한다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("request rejected: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_request");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 저장하려는 추천 초안(draft)이 없을 때 404 로 반환한다.
     *
     * ScheduleNotFoundException 은 @ResponseStatus(NOT_FOUND) 를 달고 있으나,
     * @ExceptionHandler(Exception.class) 폴백이 ExceptionHandlerExceptionResolver
     * 단계에서 먼저 매칭되어 ResponseStatusExceptionResolver 까지 도달하지 못한다.
     * 그 결과 애너테이션이 사문화되고 500 으로 나가므로 전용 핸들러가 필요하다.
     * "초안 만료/없음"(재추천 후 재시도로 복구 가능)과 "서버 장애"(재시도 무의미)를
     * 호출 측이 구분할 수 있게 한다.
     */
    @ExceptionHandler(map.service.user.schedule.ScheduleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleScheduleNotFound(
            map.service.user.schedule.ScheduleNotFoundException ex) {
        log.warn("schedule draft not found: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "draft_not_found");
        body.put("message", "저장할 추천 결과를 찾을 수 없습니다. 다시 추천을 생성해 주세요.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 저장된 일정을 찾지 못했을 때(없거나 남의 것) 404 로 반환한다.
     *
     * 저장 단계의 draft 미존재와 다른 문구를 준다 — 이쪽의 복구 방법은
     * "추천 다시 생성"이 아니라 "목록에서 다시 고르기"다.
     */
    @ExceptionHandler(map.service.user.schedule.SavedScheduleNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSavedScheduleNotFound(
            map.service.user.schedule.SavedScheduleNotFoundException ex) {
        log.warn("saved schedule not found: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "schedule_not_found");
        body.put("message", "일정을 찾을 수 없습니다.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 경로 변수·쿼리 파라미터의 타입이 맞지 않을 때 400 으로 반환한다.
     *
     * 예: /api/v1/schedules/undefined — 문자열을 Long 으로 바꿀 수 없다.
     * 전용 핸들러가 없으면 최종 폴백이 잡아 500 으로 나가서, 잘못 보낸
     * 쪽(클라이언트)의 문제가 서버 장애처럼 보인다.
     */
    @ExceptionHandler(org.springframework.web.method.annotation
            .MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation
                    .MethodArgumentTypeMismatchException ex) {
        log.warn("path/query parameter type mismatch: name={} value={}",
                ex.getName(), ex.getValue());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_parameter");
        body.put("message", ex.getName() + " 값의 형식이 올바르지 않습니다.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 일정 날짜 범위가 잘못됐을 때(시작일 > 종료일) 400 으로 반환한다.
     * 위 handleScheduleNotFound 와 같은 이유로 전용 핸들러가 필요하다.
     */
    @ExceptionHandler(map.service.user.schedule.InvalidScheduleDateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidScheduleDate(
            map.service.user.schedule.InvalidScheduleDateException ex) {
        log.warn("invalid schedule date range: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_schedule_date");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── 최종 폴백 ──────────────────────────────────────────────────────────────

    /** 위에서 처리되지 않은 모든 예외를 500 으로 변환한다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity
                .status(500)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
