package map.service.user.chat.dto;

/**
 * TypingRequest — 입력 상태 알림 요청 본문(WebSocket)
 *
 * typing 이 true 면 입력 시작, false 면 입력 중단을 뜻한다. 서버는 저장하지 않고 곧바로
 * 같은 방 참가자들에게 전달한다.
 */
public record TypingRequest(boolean typing) {
}
