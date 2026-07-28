package map.service.user.chat.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import map.service.user.global.config.ChatProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * ChatBroadcastRelay — 인스턴스 간 브로드캐스트 릴레이
 *
 * 채팅 이벤트 전달을 하나의 경로로 통일한다. 이벤트를 만든 곳은 곧바로 로컬 구독자에게
 * 보내지 않고 Redis 발행/구독 채널로 publish 만 한다. 그러면 모든 인스턴스(발행한
 * 인스턴스 포함)의 리스너가 그 메시지를 수신해 자기 인스턴스의 구독자에게 전달한다.
 * 이렇게 단일 전달 경로를 두어, 다중 인스턴스에서도 팬아웃이 일관되고 발행 인스턴스에서
 * 이중 전송이 생기지 않는다.
 *
 * publish: 봉투를 JSON 으로 직렬화해 채팅 채널로 발행한다.
 * onMessage: 채널에서 받은 봉투를 역직렬화해 해당 방 토픽(/topic/rooms/{roomId})으로
 *            로컬 구독자에게 전달한다.
 */
@Slf4j
@Component
public class ChatBroadcastRelay implements MessageListener {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate chatRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String channel;

    public ChatBroadcastRelay(SimpMessagingTemplate messagingTemplate,
                              @Qualifier("chatRedisTemplate") StringRedisTemplate chatRedisTemplate,
                              ObjectMapper objectMapper,
                              ChatProperties chatProperties) {
        this.messagingTemplate = messagingTemplate;
        this.chatRedisTemplate = chatRedisTemplate;
        this.objectMapper = objectMapper;
        this.channel = chatProperties.getBroadcastChannel();
    }

    /** 이벤트 봉투를 채팅 채널로 발행한다. 실제 로컬 전달은 onMessage 수신 시 이뤄진다. */
    public void publish(ChatEventEnvelope envelope) {
        try {
            chatRedisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("chat event serialization failed", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ChatEventEnvelope envelope =
                    objectMapper.readValue(message.getBody(), ChatEventEnvelope.class);
            messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + envelope.roomId(), envelope);
        } catch (Exception e) {
            // 한 건의 역직렬화/전달 실패가 리스너를 죽이지 않도록 삼킨다(다음 메시지 계속 처리).
            log.warn("chat broadcast relay delivery failed: {}", e.getMessage());
        }
    }
}
