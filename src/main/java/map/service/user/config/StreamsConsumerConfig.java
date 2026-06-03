package map.service.user.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import map.service.user.recommend.RecommendJobsConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

/**
 * StreamsConsumerConfig — Redis Streams 컨슈머 그룹 등록 및 리스너 컨테이너 설정
 *
 * agent 가 완료 결과를 적재하는 Redis Stream 을 BFF 측에서 소비할 수 있도록
 * 컨슈머 그룹을 보장하고, StreamMessageListenerContainer 를 빈으로 등록한다.
 *
 * 구성 요소:
 * - streamsConnectionFactory : RedisConfig 가 등록한 streams 전용 ConnectionFactory
 * - RecommendJobsConsumer    : 메시지 수신 리스너 (수신 후 명시적 ACK 도 본 리스너가 담당)
 * - 프로퍼티:
 *     - streams.recommend-stream  : 소비할 Stream 키 (기본 agent:jobs:done)
 *     - streams.recommend-group   : 컨슈머 그룹명     (기본 bff-result)
 *     - streams.recommend-consumer: 컨슈머명         (기본 user-1)
 *
 * 동작 흐름:
 * 1) PostConstruct 단계의 ensureGroup() 가 XGROUP CREATE 를 시도하고,
 *    이미 존재하는 그룹(BUSYGROUP) 오류는 정상 동작으로 간주하여 로그만 남긴다.
 * 2) streamsContainer() 가 pollTimeout 2초의 StreamMessageListenerContainer 를 생성하여
 *    Consumer(group, consumer) 로부터 lastConsumed() 오프셋부터 수신하도록 등록한다.
 * 3) 컨테이너는 등록 직후 start() 되며, 빈 소멸 시 stop() 으로 정리된다.
 *
 * 비고:
 * - 명시적 ACK 는 본 설정이 아닌 RecommendJobsConsumer 의 책임이다.
 */
@Configuration
@EnableScheduling
public class StreamsConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(StreamsConsumerConfig.class);

    /** streams 전용 Redis 연결 팩토리 (RedisConfig 가 등록). */
    private final RedisConnectionFactory factory;
    /** 수신된 Stream 메시지를 처리하는 리스너. */
    private final RecommendJobsConsumer listener;
    /** 소비할 Stream 키. */
    private final String stream;
    /** 컨슈머 그룹명. */
    private final String group;
    /** 컨슈머명. */
    private final String consumer;

    /**
     * 의존성과 프로퍼티를 주입받아 보관한다.
     *
     * @param factory   streams 전용 ConnectionFactory
     * @param listener  RecommendJobsConsumer 리스너
     * @param stream    streams.recommend-stream 프로퍼티
     * @param group     streams.recommend-group 프로퍼티
     * @param consumer  streams.recommend-consumer 프로퍼티
     */
    public StreamsConsumerConfig(
            @Qualifier("streamsConnectionFactory") RedisConnectionFactory factory,
            RecommendJobsConsumer listener,
            @Value("${streams.recommend-stream:agent:jobs:done}") String stream,
            @Value("${streams.recommend-group:bff-result}") String group,
            @Value("${streams.recommend-consumer:user-1}") String consumer
    ) {
        this.factory = factory;
        this.listener = listener;
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
    }

    /**
     * 컨슈머 그룹을 보장한다 (없으면 생성, 이미 있으면 무시).
     *
     * - XGROUP CREATE 를 ReadOffset.from("0") 으로 호출하여 처음부터 읽도록 그룹을 만든다.
     * - 이미 그룹이 존재하면 BUSYGROUP 예외가 발생하며, 이 경우 정보 로그만 남기고 무시한다.
     * - 그 외 예외는 경고 로그를 남기고 계속 진행한다 (애플리케이션 기동을 막지 않는다).
     */
    @PostConstruct
    public void ensureGroup() {
        StringRedisTemplate t = new StringRedisTemplate(factory);
        try {
            t.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("stream group created stream={} group={}", stream, group);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                log.info("stream group already exists stream={} group={}", stream, group);
            } else {
                log.warn("createGroup failed stream={} group={} reason={}",
                        stream, group, message);
            }
        }
    }

    /**
     * Stream 메시지 수신 컨테이너 빈을 생성한다.
     *
     * - pollTimeout 은 2초로 고정.
     * - Consumer(group, consumer) 로 등록하여 lastConsumed() 오프셋부터 수신한다.
     * - 등록 직후 start() 를 호출해 즉시 수신을 시작한다.
     * - destroyMethod="stop" 으로 컨텍스트 종료 시 안전하게 컨테이너를 정지시킨다.
     *
     * @return  start() 가 호출된 StreamMessageListenerContainer
     */
    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>>
            streamsContainer() {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<
                String, MapRecord<String, String, String>> opts =
                StreamMessageListenerContainer
                        .StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(factory, opts);
        container.receive(
                Consumer.from(group, consumer),
                StreamOffset.create(stream, ReadOffset.lastConsumed()),
                listener
        );
        container.start();
        return container;
    }
}
