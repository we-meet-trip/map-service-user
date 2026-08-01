package map.service.user.weather;

/**
 * WeatherUnavailableException — 현재 날씨를 만들 수 없을 때 던지는 예외
 *
 * 홈 카드의 본체인 지금 기온을 hub 에서 받지 못한 경우다. 실황이 없으면
 * 카드에 그릴 것이 없어, 빈 값을 채운 응답을 내려보내는 대신 오류로 알린다.
 * 전역 핸들러가 502 로 변환한다.
 *
 * reason: 원인 요약. 클라이언트 본문에는 싣지 않고 로그에만 남긴다.
 */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(String reason) {
        super(reason);
    }

    public WeatherUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
