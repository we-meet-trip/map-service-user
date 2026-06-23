package map.service.user.trip;

/**
 * TripGenerationException — agent 추천 잡이 실패(status=failed)했거나 결과가 비정상일 때.
 *
 * GlobalExceptionHandler 가 502 + {error, message} 로 변환해 client 에 반환한다.
 * message 는 사용자에게 노출되므로 내부 호스트/URL 을 담지 않는다.
 */
public class TripGenerationException extends RuntimeException {

    public TripGenerationException(String message) {
        super(message);
    }
}
