package map.service.user.transit;

/**
 * SubwayRouteException — hub 지하철 경로 조회 실패 표현 예외
 *
 * SubwayRouteClient 가 hub 로부터 4xx/5xx 응답을 받았을 때 던지는
 * RuntimeException. 호출자/전역 핸들러가 상태코드와 본문을 검사할 수 있도록
 * 두 값을 보관한다. 기본 메시지에는 본문 앞 200자만 잘라 포함한다.
 *
 * 외부 조회가 실패한 경우는 여기로 오지 않는다. hub 가 그런 경우를 오류가
 * 아니라 응답의 status 로 알려 주기 때문이다. 이 예외는 hub 자체에 닿지
 * 못했거나 계약이 어긋났을 때만 발생한다.
 *
 * statusCode: hub 응답의 HTTP 상태 코드.
 * body: hub 응답 본문 원문. null 가능.
 */
public class SubwayRouteException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public SubwayRouteException(int statusCode, String body) {
        super("subway route " + statusCode + ": " + truncate(body, 200));
        this.statusCode = statusCode;
        this.body = body;
    }

    /** 저장된 상태 코드 반환. */
    public int statusCode() {
        return statusCode;
    }

    /** 저장된 응답 본문 원문 반환. null 가능. */
    public String body() {
        return body;
    }

    /** 응답 본문을 max 길이로 자른 문자열 반환. */
    public String truncatedBody(int max) {
        return truncate(body, max);
    }

    /**
     * 문자열을 max 길이로 자르는 정적 유틸.
     *
     * null → "" 반환. 길이가 max 이하이면 원문, 초과이면 substring(0, max).
     */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
