package map.service.user.chat.config;

import java.util.List;
import map.service.user.chat.ws.StompAuthChannelInterceptor;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.config.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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

    /** 하트비트 주기. 통신사 장비가 유휴 연결을 걷어가기 전에 오가도록 짧게 둔다. */
    private static final long HEARTBEAT_MS = 10_000L;

    /**
     * 하트비트 전용 일꾼. 브로커가 주기적으로 프레임을 내보내려면 스케줄러가
     * 필요한데, 기본 설정은 그것을 브로커에 넣어 주지 않는다.
     */
    @Bean
    public TaskScheduler chatHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-heartbeat-");
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(new map.service.user.chat.ws.SafeStompErrorHandler());
        // WebSocket 은 자격증명 여부와 무관하게 allowedOriginPatterns 로 출처를 지정한다.
        List<String> origins = corsProperties.getAllowedOrigins();
        registry.addEndpoint(chatProperties.getWsEndpoint())
                .setAllowedOriginPatterns(origins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic 은 방 브로드캐스트, /queue 는 보낸 사람 한 명에게만 가는 통지에 쓴다.
        // /queue 를 빼면 사용자 전용 목적지(/user/queue/**)가 브로커에 등록되지 않아
        // 구독도 전달도 조용히 무시된다 — 실패 통지가 사라지는 형태로 드러난다.
        // 하트비트를 돌릴 일꾼을 함께 준다. 주지 않으면 브로커가 하트비트 값을
        // 정하지 못해 접속 응답에 0,0 을 실어 보내고, 그러면 클라이언트가 요청한
        // 주기까지 양쪽 모두 무효가 된다. 그 상태에서는 오가는 것이 없는 소켓을
        // 통신사 장비가 조용히 끊어도 양쪽 다 끊긴 줄 모른다 — 보내는 쪽은
        // 연결됐다고 믿고 소켓으로 보내므로 REST 로 되돌아가지도 않는다.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(chatHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new org.springframework.messaging.support.ChannelInterceptor() {
            @Override public org.springframework.messaging.Message<?> preSend(
                    org.springframework.messaging.Message<?> message, org.springframework.messaging.MessageChannel channel) {
                return authChannelInterceptor.authorizeOutbound(message);
            }
        });
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
