package map.service.user.chat.config;

import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.global.config.ChatProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * ChatRelayConfig — 채팅 발행/구독 리스너 컨테이너 구성
 *
 * 채팅 전용 연결 팩토리로 RedisMessageListenerContainer 를 만들어 브로드캐스트 채널을
 * 구독하고, 수신 메시지를 ChatBroadcastRelay 로 넘긴다. 이 컨테이너는 모든 인스턴스에서
 * 동작하므로, 어느 인스턴스가 발행하든 각 인스턴스가 자기 로컬 구독자에게 이벤트를 전달한다.
 */
@Configuration
public class ChatRelayConfig {

    @Bean
    public RedisMessageListenerContainer chatMessageListenerContainer(
            @Qualifier("chatConnectionFactory") RedisConnectionFactory chatConnectionFactory,
            ChatBroadcastRelay relay,
            ChatProperties chatProperties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(chatConnectionFactory);
        container.addMessageListener(relay, new ChannelTopic(chatProperties.getBroadcastChannel()));
        return container;
    }
}
