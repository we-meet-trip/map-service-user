package map.service.user.trip;

/**
 * TripTimeoutException — 동기 facade 가 폴링 한도 내에 추천 결과(draft)를 받지 못했을 때.
 *
 * GlobalExceptionHandler 가 504 + {error, message} 로 변환한다. agent 의
 * JOB_TIMEOUT_SECONDS 보다 길거나 같은 폴링 상한(trip.poll-timeout-seconds)을 권장한다.
 */
public class TripTimeoutException extends RuntimeException {

    public TripTimeoutException(String jobId) {
        super("recommendation job timed out: " + jobId);
    }
}
