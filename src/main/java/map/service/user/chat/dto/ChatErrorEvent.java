package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ChatErrorEvent — 실시간 전송 실패 통지
 *
 * WebSocket 으로 보낸 요청이 실패했을 때 그 사실을 보낸 사람에게만 돌려주는 페이로드다.
 * REST 로 같은 요청을 보냈다면 HTTP 상태와 본문으로 받았을 정보를 같은 모양으로 담아,
 * 두 경로가 같은 실패를 같은 코드로 알리게 한다.
 *
 * code:        실패 사유 코드. REST 응답 본문의 code 와 같은 값이다.
 * message:     사람이 읽는 사유.
 * status:      REST 로 같은 요청을 보냈을 때 받았을 HTTP 상태. 클라이언트가 이미 REST
 *              기준으로 분기하고 있다면 그 분기를 그대로 재사용할 수 있다.
 * destination: 실패한 요청의 목적지(예: /app/rooms/3/send). 한 연결로 여러 방과 여러
 *              동작을 보내므로, 무엇이 실패했는지 짚어 주지 않으면 어느 말풍선을
 *              실패로 표시할지 알 수 없다.
 */
public record ChatErrorEvent(
        String code,
        String message,
        int status,
        String destination
) {
    @JsonProperty("type")
    public String type() {
        return "ERROR";
    }
}
