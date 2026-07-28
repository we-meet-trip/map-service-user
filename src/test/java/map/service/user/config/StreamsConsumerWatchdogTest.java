package map.service.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import map.service.user.recommend.RecommendJobsConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

/**
 * StreamsConsumerWatchdogTest — 구독 재구독 워치독 단위 테스트
 *
 * resubscribeIfInactive() 가
 * ① 구독 비활성 시 remove 후 재구독하여 새 Subscription 으로 교체하고,
 * ② 구독 활성 시 컨테이너에 아무 동작도 하지 않음을 검증한다.
 *
 * 순수 Mockito 단위 테스트(Spring 컨텍스트·Redis 미접촉). 생성자는 의존성/프로퍼티만
 * 보관하고 Redis 를 건드리지 않으며(ensureGroup 은 @PostConstruct 라 미호출), 워치독이
 * 사용하는 container/subscription 은 동일 패키지 package-private 필드로 직접 주입한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StreamsConsumerConfig 워치독 재구독 단위 테스트")
class StreamsConsumerWatchdogTest {

    @Mock private RedisConnectionFactory factory;
    @Mock private RecommendJobsConsumer listener;
    @Mock private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    @Mock private Subscription subscription;
    @Mock private Subscription newSubscription;

    private StreamsConsumerConfig config;

    @BeforeEach
    void setUp() {
        config = new StreamsConsumerConfig(
                factory, listener, "agent:jobs:done", "bff-result", "user-1");
        config.container = container;
        config.subscription = subscription;
    }

    @Test
    @DisplayName("구독 비활성 → remove 후 재구독하고 새 Subscription 으로 교체")
    void resubscribesWhenInactive() {
        when(subscription.isActive()).thenReturn(false);
        when(container.receive(any(), any(), any())).thenReturn(newSubscription);

        config.resubscribeIfInactive();

        verify(container).remove(subscription);
        verify(container).receive(any(), any(), any());
        assertThat(config.subscription).isSameAs(newSubscription);
    }

    @Test
    @DisplayName("구독 활성 → 컨테이너에 아무 동작도 하지 않음")
    void noopWhenActive() {
        when(subscription.isActive()).thenReturn(true);

        config.resubscribeIfInactive();

        verifyNoInteractions(container);
        assertThat(config.subscription).isSameAs(subscription);
    }
}
