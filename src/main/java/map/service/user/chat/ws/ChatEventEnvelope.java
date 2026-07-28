package map.service.user.chat.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import map.service.user.chat.dto.MessageResponse;

/**
 * ChatEventEnvelope — 서버→클라이언트 실시간 이벤트 봉투
 *
 * 방마다 하나의 토픽으로 모든 종류의 실시간 이벤트를 흘려보내되, type 으로 종류를
 * 구분한다. data 에는 종류별 payload 가 담긴다. 클라이언트는 type 으로 분기하고 data 를
 * 해석한다. roomId 는 인스턴스 간 발행/구독 릴레이에서 어느 방 토픽으로 보낼지 결정하는
 * 라우팅 정보이기도 하다.
 *
 * type 값: MESSAGE(텍스트) · SYSTEM(안내/카드) · READ(읽음) · TYPING(입력중) ·
 *          PRESENCE(접속) · ROOM_CLOSED(보관 전환).
 */
public record ChatEventEnvelope(String type, @JsonProperty("room_id") long roomId, Object data) {

    /**
     * 저장된 메시지를 봉투로 감싼다. 메시지 종류가 SYSTEM 이면 SYSTEM, 그 외에는 MESSAGE
     * 타입으로 브로드캐스트한다.
     */
    public static ChatEventEnvelope message(MessageResponse message) {
        String type = "SYSTEM".equals(message.type()) ? "SYSTEM" : "MESSAGE";
        return new ChatEventEnvelope(type, message.roomId(), message);
    }

    /** 읽음 이벤트를 봉투로 감싼다. */
    public static ChatEventEnvelope read(long roomId, long userId, long lastReadSeq) {
        return new ChatEventEnvelope("READ", roomId, new ReadEvent(userId, lastReadSeq));
    }

    /** 입력 상태 이벤트를 봉투로 감싼다. */
    public static ChatEventEnvelope typing(long roomId, long userId, boolean typing) {
        return new ChatEventEnvelope("TYPING", roomId, new TypingEvent(userId, typing));
    }

    /** 접속 상태 이벤트를 봉투로 감싼다. */
    public static ChatEventEnvelope presence(long roomId, long userId, boolean online) {
        return new ChatEventEnvelope("PRESENCE", roomId, new PresenceEvent(userId, online));
    }

    /** 방이 보관 전용으로 전환됐음을 알리는 이벤트를 봉투로 감싼다. payload 는 없다. */
    public static ChatEventEnvelope roomClosed(long roomId) {
        return new ChatEventEnvelope("ROOM_CLOSED", roomId, null);
    }

    /** 임의 종류의 이벤트를 봉투로 감싼다. */
    public static ChatEventEnvelope of(String type, long roomId, Object data) {
        return new ChatEventEnvelope(type, roomId, data);
    }
}
