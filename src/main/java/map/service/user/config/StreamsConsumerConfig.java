package map.service.user.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import map.service.user.recommend.RecommendJobsConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

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
 * 4) resubscribeIfInactive() 워치독이 streams.watchdog-interval-ms 주기로 구독 활성
 *    상태를 감시한다. 일시적 Redis command timeout 등으로 폴 태스크가 죽어 구독이
 *    비활성이 되면 remove 후 재구독하여 영구 소비 중단(→ trip/generate 504)을 막는다.
 *
 * 비고:
 * - 명시적 ACK 는 본 설정이 아닌 RecommendJobsConsumer 의 책임이다.
 * - errorHandler 는 폴 오류 로깅(가시성)용이며 구독을 되살리지 못한다(복구는 워치독).
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
     * 리스너 컨테이너. streamsContainer() 빈 생성 시점에 채워진다.
     * 워치독(resubscribeIfInactive)이 재구독 시 사용하므로 필드로 보관한다.
     * 부팅 스레드에서 쓰고 스케줄러 스레드에서 읽으므로 안전 발행 위해 volatile.
     */
    volatile StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    /**
     * 현재 활성 구독 핸들. 폴 태스크가 예외로 죽으면 비활성(isActive()=false)이 되며,
     * 워치독이 이를 감지해 remove 후 재구독한 새 핸들로 교체한다. volatile 로 발행한다.
     */
    volatile Subscription subscription;

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
     * - Stream 키가 아직 없으면(agent 가 첫 XADD 하기 전 BFF 가 먼저 기동한 경우)
     *   XGROUP CREATE 가 "key must exist"(MKSTREAM 안내) 오류로 실패하고 그룹이
     *   만들어지지 않는다. 이를 막기 위해 키 존재를 먼저 확인하고, 없으면 더미
     *   레코드를 추가해 빈 Stream 을 만든 뒤 그룹을 생성한다. 더미는 job_id/payload
     *   가 없어 RecommendJobsConsumer.onMessage 가 ack 후 폐기하므로 안전하다.
     * - XGROUP CREATE 를 ReadOffset.from("0") 으로 호출하여 처음부터 읽도록 그룹을 만든다.
     * - 이미 그룹이 존재하면 BUSYGROUP 예외가 발생하며, 이 경우 정보 로그만 남기고 무시한다.
     * - 그 외 예외는 경고 로그를 남기고 계속 진행한다 (애플리케이션 기동을 막지 않는다).
     */
    @PostConstruct
    public void ensureGroup() {
        StringRedisTemplate t = new StringRedisTemplate(factory);
        try {
            if (Boolean.FALSE.equals(t.hasKey(stream))) {
                MapRecord<String, String, String> init = StreamRecords
                        .mapBacked(Map.of("_init", "1"))
                        .withStreamKey(stream);
                t.opsForStream().add(init);
                log.info("stream initialized with placeholder stream={}", stream);
            }
            t.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("stream group created stream={} group={}", stream, group);
        } catch (RuntimeException e) {
            // Lettuce 는 Redis 오류를 래핑하여 getMessage() 가 "Error in execution"
            // 처럼 모호할 수 있으므로, 원인 체인 전체를 훑어 BUSYGROUP(그룹 기존재)을
            // 판정한다. 기존재는 정상으로 간주하고 정보 로그만 남긴다.
            if (causeChainContains(e, "BUSYGROUP")) {
                log.info("stream group already exists stream={} group={}", stream, group);
            } else {
                log.warn("createGroup failed stream={} group={} reason={}",
                        stream, group, rootCauseMessage(e));
            }
        }
    }

    /** 예외 원인 체인에 needle 을 포함하는 메시지가 있으면 true. */
    private static boolean causeChainContains(Throwable e, String needle) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 예외 원인 체인 최하위(가장 구체적인 Redis 오류) 메시지를 반환한다. */
    private static String rootCauseMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage();
    }

    /**
     * Stream 메시지 수신 컨테이너 빈을 생성한다.
     *
     * - pollTimeout 은 2초로 고정.
     * - errorHandler 로 폴링 오류를 로깅한다. 단, 이 핸들러는 오류를 관찰만 하며 구독을
     *   되살리지 못한다. spring-data-redis 의 기본 receive() 경로는
     *   cancelSubscriptionOnError=(t->true) 이라, 폴 예외 발생 시 errorHandler 실행
     *   이전에 구독이 cancel 되고 폴 루프가 영구 종료된다. 실제 복구는 워치독
     *   resubscribeIfInactive() 가 담당한다.
     * - Consumer(group, consumer) 로 등록하여 lastConsumed() 오프셋부터 수신하고,
     *   반환된 Subscription 을 필드에 보관한다(subscribe()).
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
                        .errorHandler(t -> log.warn(
                                "stream poll error stream={} group={} reason={}",
                                stream, group, t.getMessage()))
                        .build();
        this.container = StreamMessageListenerContainer.create(factory, opts);
        subscribe();
        container.start();
        return container;
    }

    /**
     * Consumer(group, consumer) 로 lastConsumed() 오프셋부터 수신하도록 구독하고
     * 반환된 Subscription 핸들을 필드에 보관한다. 빈 생성과 워치독 재구독이 공용한다.
     *
     * lastConsumed()(= XREADGROUP ">") 는 아직 그룹에 미전달된 엔트리를 읽으므로,
     * 컨슈머 다운타임 중 XADD 되어 미전달로 남은 엔트리를 재구독 시 회수한다.
     * 이미 전달됐으나 미ack 된 PEL 잔여분은 RecommendJobsConsumer.reclaimPending()
     * 스케줄러가 담당하므로 본 메서드가 중복 처리하지 않는다.
     */
    private void subscribe() {
        this.subscription = container.receive(
                Consumer.from(group, consumer),
                StreamOffset.create(stream, ReadOffset.lastConsumed()),
                listener
        );
    }

    /**
     * 워치독 — 구독이 비활성이면(폴링 중 예외로 cancel 된 상태) 재구독한다.
     *
     * 일시적 Redis command timeout 등으로 폴 태스크가 죽으면 Subscription 이
     * 비활성(isActive()=false)이 되어 더 이상 메시지를 수신하지 못한다. 본 워치독이
     * 주기적으로 이를 감지하여 죽은 구독을 remove 하고 동일 파라미터로 재구독한다.
     * 정상 구독 중이면 아무 것도 하지 않아 idempotent 하다. 기본 스케줄러는 단일
     * 스레드라 실행이 중첩되지 않으므로 이중 구독이 생기지 않는다.
     *
     * package-private: 동일 패키지 단위 테스트에서 직접 호출·검증하기 위함.
     */
    @Scheduled(fixedDelayString = "${streams.watchdog-interval-ms:10000}",
            initialDelayString = "${streams.watchdog-interval-ms:10000}")
    void resubscribeIfInactive() {
        if (container == null) {
            return; // 컨테이너 빈이 아직 생성되지 않음 (정상 기동에서는 발생하지 않음)
        }
        Subscription current = this.subscription;
        if (current != null && current.isActive()) {
            return; // 정상 구독 중 — 아무 것도 하지 않음
        }
        if (current != null) {
            container.remove(current); // 죽은 구독 정리 (이미 cancel 되었어도 안전)
        }
        subscribe();
        log.warn("stream 구독이 비활성 상태여서 재구독함 stream={} group={} consumer={}",
                stream, group, consumer);
    }
}
