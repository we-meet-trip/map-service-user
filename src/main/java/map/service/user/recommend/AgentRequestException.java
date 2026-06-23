package map.service.user.recommend;

/**
 * AgentRequestException — agent 호출 실패 표현 예외
 *
 * AgentClient 가 4xx/5xx 응답을 받았을 때 던지는 RuntimeException.
 * 호출자가 상태코드와 본문을 검사해 후속 처리를 할 수 있도록 두 값을 보관한다.
 * 기본 메시지에는 본문 앞 200자만 truncate 하여 포함된다.
 *
 * statusCode: agent 응답의 HTTP 상태 코드.
 * body: agent 응답 본문 전체. null 가능.
 */
public class AgentRequestException extends RuntimeException {

    private final int statusCode;
    private final String body;

    /**
     * 상태코드와 응답 본문으로 예외를 생성.
     *
     * 슈퍼클래스 메시지는 "agent {statusCode}: {본문 앞 200자}" 형식이다.
     *
     * statusCode: agent 응답 HTTP 상태 코드.
     * body: 응답 본문 원문. null 가능.
     */
    public AgentRequestException(int statusCode, String body) {
        super("agent " + statusCode + ": " + truncate(body, 200));
        this.statusCode = statusCode;
        this.body = body;
    }

    /**
     * 저장된 상태 코드 반환.
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * 저장된 응답 본문 원문 반환. null 가능.
     */
    public String body() {
        return body;
    }

    /**
     * 응답 본문을 max 길이로 자른 문자열 반환.
     *
     * 본문이 null 이면 빈 문자열, 길이가 max 이하이면 원문, 초과이면 앞에서부터 max 자.
     *
     * max: 최대 길이.
     */
    public String truncatedBody(int max) {
        return truncate(body, max);
    }

    /**
     * 문자열을 max 길이로 자르는 정적 유틸.
     *
     * null → "" 반환. 길이가 max 이하이면 원문 반환. 초과이면 substring(0, max).
     *
     * text: 자를 원문.
     * max: 최대 길이.
     */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
