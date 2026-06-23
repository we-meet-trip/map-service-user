package map.service.user.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * RedisConfig — 용도별 Redis 연결 팩토리·템플릿 빈 설정
 *
 * 동일한 Redis 인스턴스를 용도(Stream / Counter / Draft)별로 서로 다른
 * DB 번호에 분리하여 사용한다. 각 용도마다 별도 LettuceConnectionFactory 빈을
 * 등록하고, 일부 용도(Draft / Counter)에는 StringRedisTemplate 도 함께 등록한다.
 *
 * 공통 동작:
 * - Lettuce 의 commandTimeout 은 3초, socketConnectTimeout 도 3초로 고정한다.
 * - host / port 는 spring.data.redis.host / spring.data.redis.port 프로퍼티에서
 *   주입받는다. (기본값: redis / 6379)
 * - 각 ConnectionFactory 는 destroyMethod="destroy" 로 컨테이너 종료 시 정리된다.
 *
 * 등록되는 빈:
 * - streamsConnectionFactory  : redis.db-streams (기본 2)  — Streams 컨슈머 그룹
 * - countersConnectionFactory : redis.db-counters (기본 3) — 카운터 / 정량 상태
 * - draftsConnectionFactory   : redis.db-drafts (기본 4)   — 임시 초안 데이터
 * - draftsRedisTemplate       : draftsConnectionFactory 위에 얹는 StringRedisTemplate
 * - countersRedisTemplate     : countersConnectionFactory 위에 얹는 StringRedisTemplate
 *
 * 참고:
 * - ServiceUserApplication 이 RedisRepositoriesAutoConfiguration 을 제외하므로
 *   Spring Data Redis Repository 자동 활성화는 비활성 상태이다.
 * - Stream 메시지 수신은 StreamsConsumerConfig 가 streamsConnectionFactory 를 받아
 *   StreamMessageListenerContainer 를 별도로 등록한다.
 */
@Configuration
public class RedisConfig {

    /** Redis 호스트명. spring.data.redis.host 프로퍼티에서 주입. */
    private final String host;
    /** Redis 포트. spring.data.redis.port 프로퍼티에서 주입. */
    private final int port;
    /**
     * 3개 ConnectionFactory 가 공유하는 Lettuce 리소스(Netty 이벤트 루프·스레드풀).
     * 팩토리마다 암묵적으로 별도 생성하면 스레드풀이 3벌로 늘어나므로 단일
     * 인스턴스를 공유하고, 소유자인 본 설정이 @PreDestroy 에서 종료한다.
     */
    private final ClientResources clientResources = DefaultClientResources.create();

    /**
     * 공용 host / port 를 프로퍼티에서 받아 보관한다.
     *
     * @param host  Redis 호스트명 (기본 redis)
     * @param port  Redis 포트     (기본 6379)
     */
    public RedisConfig(
            @Value("${spring.data.redis.host:redis}") String host,
            @Value("${spring.data.redis.port:6379}") int port
    ) {
        this.host = host;
        this.port = port;
    }

    /**
     * 지정된 DB 번호로 LettuceConnectionFactory 를 생성한다.
     *
     * - commandTimeout / socketConnectTimeout 모두 3초로 고정.
     * - afterPropertiesSet() 를 즉시 호출하여 컨테이너 초기화 전에 연결을 준비한다.
     *
     * @param database  사용할 Redis 논리 DB 번호
     * @return          초기화 완료된 LettuceConnectionFactory
     */
    private LettuceConnectionFactory build(int database) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(database);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientResources(clientResources)
                .commandTimeout(Duration.ofSeconds(3))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .build())
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * Streams 용 ConnectionFactory.
     *
     * 사용처: StreamsConsumerConfig 가 Qualifier("streamsConnectionFactory") 로 주입받아
     *        ensureGroup() 및 StreamMessageListenerContainer 생성에 사용한다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-streams, 기본 2)
     */
    @Bean(name = "streamsConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory streamsConnectionFactory(
            @Value("${redis.db-streams:2}") int db
    ) {
        return build(db);
    }

    /**
     * 카운터 용 ConnectionFactory.
     *
     * 사용처: countersRedisTemplate 빈에 주입되어 카운터 키 조작에 사용된다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-counters, 기본 3)
     */
    @Bean(name = "countersConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory countersConnectionFactory(
            @Value("${redis.db-counters:3}") int db
    ) {
        return build(db);
    }

    /**
     * 임시 초안(draft) 용 ConnectionFactory.
     *
     * 사용처: draftsRedisTemplate 빈에 주입되어 초안 데이터의 저장 / 조회에 사용된다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-drafts, 기본 4)
     */
    @Bean(name = "draftsConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory draftsConnectionFactory(
            @Value("${redis.db-drafts:4}") int db
    ) {
        return build(db);
    }

    /**
     * 초안 전용 StringRedisTemplate.
     *
     * - draftsConnectionFactory 를 명시적 Qualifier 로 주입받는다.
     * - 키 / 값 모두 String 직렬화기를 사용한다.
     *
     * @param factory  draftsConnectionFactory
     * @return         draft 키 조작용 StringRedisTemplate
     */
    @Bean(name = "draftsRedisTemplate")
    public StringRedisTemplate draftsRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("draftsConnectionFactory")
            RedisConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 카운터 전용 StringRedisTemplate.
     *
     * - countersConnectionFactory 를 명시적 Qualifier 로 주입받는다.
     * - 키 / 값 모두 String 직렬화기를 사용한다.
     *
     * @param factory  countersConnectionFactory
     * @return         카운터 키 조작용 StringRedisTemplate
     */
    @Bean(name = "countersRedisTemplate")
    public StringRedisTemplate countersRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("countersConnectionFactory")
            RedisConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 컨텍스트 종료 시 공유 ClientResources 를 정리한다.
     *
     * ConnectionFactory 는 외부에서 주입한 ClientResources 를 스스로 종료하지
     * 않으므로(소유권 비보유), 생성한 본 설정이 책임진다. Spring 은 빈을 역순으로
     * 소멸시키므로 3개 팩토리(destroy)가 먼저 정리된 뒤 본 메서드가 실행된다.
     */
    @PreDestroy
    public void shutdownClientResources() {
        clientResources.shutdown();
    }
}
