package map.service.user.chat.config;

import java.util.List;
import map.service.user.chat.ws.StompAuthChannelInterceptor;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.config.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * ChatWebSocketConfig — STOMP over WebSocket 브로커 구성
 *
 * 실시간 채팅의 전송 계층을 설정한다.
 *
 * - 엔드포인트: 클라이언트가 연결하는 핸드셰이크 경로(ws-endpoint). 허용 출처는 CORS
 *   설정을 그대로 따른다.
 * - 브로커: 인메모리 SimpleBroker 를 /topic 접두사로 사용하고, 클라이언트가 서버로
 *   보내는 목적지는 /app 접두사로, 사용자 전용 목적지는 /user 접두사로 둔다.
 * - 인바운드 채널: 인증·인가 인터셉터를 끼워 CONNECT/SUBSCRIBE 프레임을 검사한다.
 *
 * 다중 인스턴스 팬아웃은 별도의 Redis 발행/구독 릴레이가 담당하며, 여기서는 로컬
 * 브로커만 구성한다.
 */
@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatProperties chatProperties;
    private final CorsProperties corsProperties;
    private final StompAuthChannelInterceptor authChannelInterceptor;

    public ChatWebSocketConfig(ChatProperties chatProperties,
                               CorsProperties corsProperties,
                               StompAuthChannelInterceptor authChannelInterceptor) {
        this.chatProperties = chatProperties;
        this.corsProperties = corsProperties;
        this.authChannelInterceptor = authChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 은 자격증명 여부와 무관하게 allowedOriginPatterns 로 출처를 지정한다.
        List<String> origins = corsProperties.getAllowedOrigins();
        registry.addEndpoint(chatProperties.getWsEndpoint())
                .setAllowedOriginPatterns(origins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
