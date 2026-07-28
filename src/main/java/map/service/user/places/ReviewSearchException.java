package map.service.user.places;

/**
 * ReviewSearchException — hub 리뷰 조회 실패 표현 예외
 *
 * ReviewSearchClient 가 hub 로부터 4xx/5xx 응답을 받았을 때 던지는
 * RuntimeException. 호출자/전역 핸들러가 상태코드와 본문을 검사할 수 있도록
 * 두 값을 보관한다. 기본 메시지에는 본문 앞 200자만 잘라 포함한다.
 *
 * statusCode: hub 응답의 HTTP 상태 코드.
 * body: hub 응답 본문 원문. null 가능.
 */
public class ReviewSearchException extends RuntimeException {

    private final int statusCode;
    private final String body;

    public ReviewSearchException(int statusCode, String body) {
        super("review search " + statusCode + ": " + truncate(body, 200));
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
