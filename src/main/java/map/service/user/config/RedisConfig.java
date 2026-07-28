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
import org.springframework.data.redis.connection.RedisPassword;
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
 * - Lettuce 의 commandTimeout 은 build() 인자로 주입하며(streams 는 여유를 둔 값,
 *   그 외 용도는 3초), socketConnectTimeout 은 모든 용도에서 3초로 고정한다.
 * - host / port 는 spring.data.redis.host / spring.data.redis.port 프로퍼티에서
 *   주입받는다. (기본값: redis / 6379)
 * - 각 ConnectionFactory 는 destroyMethod="destroy" 로 컨테이너 종료 시 정리된다.
 *
 * 등록되는 빈:
 * - streamsConnectionFactory  : redis.db-streams (기본 2)  — Streams 컨슈머 그룹
 * - countersConnectionFactory : redis.db-counters (기본 3) — 카운터 / 정량 상태
 * - draftsConnectionFactory   : redis.db-drafts (기본 4)   — 임시 초안 데이터
 * - cacheConnectionFactory    : redis.db-cache (기본 6)    — 재사용 캐시(ReuseCacheStore)
 * - draftsRedisTemplate       : draftsConnectionFactory 위에 얹는 StringRedisTemplate
 * - countersRedisTemplate     : countersConnectionFactory 위에 얹는 StringRedisTemplate
 * - cacheRedisTemplate        : cacheConnectionFactory 위에 얹는 StringRedisTemplate
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
     * Redis AUTH 비밀번호. spring.data.redis.password 에서 주입.
     * 비어 있으면(기본) 모든 팩토리에 미적용 — 현행 무인증 동작을 보존한다.
     */
    private final String password;
    /**
     * 3개 ConnectionFactory 가 공유하는 Lettuce 리소스(Netty 이벤트 루프·스레드풀).
     * 팩토리마다 암묵적으로 별도 생성하면 스레드풀이 3벌로 늘어나므로 단일
     * 인스턴스를 공유하고, 소유자인 본 설정이 @PreDestroy 에서 종료한다.
     */
    private final ClientResources clientResources = DefaultClientResources.create();

    /**
     * 공용 host / port / password 를 프로퍼티에서 받아 보관한다.
     *
     * @param host      Redis 호스트명 (기본 redis)
     * @param port      Redis 포트     (기본 6379)
     * @param password  Redis AUTH 비밀번호 (기본 빈 값 = 무인증)
     */
    public RedisConfig(
            @Value("${spring.data.redis.host:redis}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password
    ) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    /**
     * 지정된 DB 번호로 LettuceConnectionFactory 를 생성한다.
     *
     * - commandTimeout 은 인자 cmdTimeout 으로 주입한다(용도별 차등: streams 는 폴링
     *   BLOCK 에 여유를 둔 값, 그 외 용도는 3초).
     * - socketConnectTimeout 은 TCP 연결 수립 시간으로, 명령 실행 지연(commandTimeout)과
     *   별개 관심사이므로 모든 용도에서 3초로 고정한다(재연결/failover 감지 지연 방지).
     * - afterPropertiesSet() 를 즉시 호출하여 컨테이너 초기화 전에 연결을 준비한다.
     *
     * @param database    사용할 Redis 논리 DB 번호
     * @param cmdTimeout  Lettuce commandTimeout
     * @return            초기화 완료된 LettuceConnectionFactory
     */
    private LettuceConnectionFactory build(int database, Duration cmdTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(database);
        // 비밀번호가 설정된 배포에서만 AUTH 를 적용한다. 비어 있으면 미적용(무인증).
        if (password != null && !password.isBlank()) {
            standalone.setPassword(RedisPassword.of(password));
        }
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientResources(clientResources)
                .commandTimeout(cmdTimeout)
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
     * 다른 용도(3초)와 달리 commandTimeout 을 streams.command-timeout-sec(기본 6초)로
     * 주입한다. streams 컨슈머는 pollTimeout 2초의 blocking XREADGROUP 이라 3초로는
     * 여유가 1초뿐이어서, 일시적 지연 시 RedisCommandTimeoutException 으로 폴 태스크가
     * 죽을 수 있다. 여유를 두어 spurious timeout 을 줄인다.
     *
     * @param db             사용할 Redis DB 번호 (redis.db-streams, 기본 2)
     * @param cmdTimeoutSec  streams commandTimeout 초 (streams.command-timeout-sec, 기본 6)
     */
    @Bean(name = "streamsConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory streamsConnectionFactory(
            @Value("${redis.db-streams:2}") int db,
            @Value("${streams.command-timeout-sec:6}") int cmdTimeoutSec
    ) {
        return build(db, Duration.ofSeconds(cmdTimeoutSec));
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
        return build(db, Duration.ofSeconds(3));
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
        return build(db, Duration.ofSeconds(3));
    }

    /**
     * 재사용 캐시(reuse cache) 용 ConnectionFactory.
     *
     * 사용처: ReuseCacheStore 가 Qualifier("cacheRedisTemplate") 로 주입받는
     *        StringRedisTemplate 의 기반이 된다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-cache, 기본 6 — DB5 는 채팅(경계 B8)이
     *            선점했으므로 충돌을 피해 DB6 을 쓴다)
     */
    @Bean(name = "cacheConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory cacheConnectionFactory(
            @Value("${redis.db-cache:6}") int db
    ) {
        // 다른 용도별 팩토리와 동일하게 command timeout 3s 를 고정한다.
        // (1-arg build(int) 오버로드는 streams command timeout 파라미터화 때 제거되었다)
        return build(db, Duration.ofSeconds(3));
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
     * JWT 블랙리스트 용 ConnectionFactory.
     *
     * 사용처: blacklistRedisTemplate 빈에 주입되어 access token jti 의
     *        블랙리스트 등록 / 조회에 사용된다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-blacklist, 기본 1)
     */
    @Bean(name = "blacklistConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory blacklistConnectionFactory(
            @Value("${redis.db-blacklist:1}") int db
    ) {
        return build(db, Duration.ofSeconds(3));
    }

    /**
     * 인증 레이트리밋 용 ConnectionFactory.
     *
     * 사용처: rateLimitRedisTemplate 빈에 주입되어 인증 시도 카운터 조작에 사용된다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-ratelimit, 기본 3)
     */
    @Bean(name = "rateLimitConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory rateLimitConnectionFactory(
            @Value("${redis.db-ratelimit:3}") int db
    ) {
        return build(db, Duration.ofSeconds(3));
    }

    /**
     * JWT 블랙리스트 전용 StringRedisTemplate.
     *
     * - blacklistConnectionFactory 를 명시적 Qualifier 로 주입받는다.
     * - JwtService 가 본 템플릿으로 access token jti 를 등록 / 조회한다.
     *
     * @param factory  blacklistConnectionFactory
     * @return         블랙리스트 키 조작용 StringRedisTemplate
     */
    @Bean(name = "blacklistRedisTemplate")
    public StringRedisTemplate blacklistRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("blacklistConnectionFactory")
            RedisConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 인증 레이트리밋 전용 StringRedisTemplate.
     *
     * - rateLimitConnectionFactory 를 명시적 Qualifier 로 주입받는다.
     * - RateLimitService 가 본 템플릿으로 인증 시도 카운터를 조작한다.
     *
     * @param factory  rateLimitConnectionFactory
     * @return         레이트리밋 키 조작용 StringRedisTemplate
     */
    @Bean(name = "rateLimitRedisTemplate")
    public StringRedisTemplate rateLimitRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("rateLimitConnectionFactory")
            RedisConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 채팅 용 ConnectionFactory.
     *
     * 사용처: chatRedisTemplate(발행 및 프레즌스 키)과 채팅 발행/구독 리스너 컨테이너에
     *        주입되어 인스턴스 간 메시지 팬아웃과 접속 상태 키 조작에 사용된다.
     *
     * 발행/구독 채널은 논리 DB 번호와 무관하게 동작하지만, 프레즌스 키(SET/EXPIRE)는
     * DB 스코프이므로 채팅 전용 DB(redis.db-chat, 기본 5)를 할당한다.
     *
     * @param db  사용할 Redis DB 번호 (redis.db-chat, 기본 5)
     */
    @Bean(name = "chatConnectionFactory", destroyMethod = "destroy")
    public RedisConnectionFactory chatConnectionFactory(
            @Value("${redis.db-chat:5}") int db
    ) {
        return build(db, Duration.ofSeconds(3));
    }

    /**
     * 채팅 전용 StringRedisTemplate.
     *
     * - chatConnectionFactory 를 명시적 Qualifier 로 주입받는다.
     * - 브로드캐스트 발행(convertAndSend)과 프레즌스 키 조작에 사용된다.
     *
     * @param factory  chatConnectionFactory
     * @return         채팅 키/채널 조작용 StringRedisTemplate
     */
    @Bean(name = "chatRedisTemplate")
    public StringRedisTemplate chatRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("chatConnectionFactory")
            RedisConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 재사용 캐시 전용 StringRedisTemplate.
     *
     * - cacheConnectionFactory 를 명시적 Qualifier 로 주입받는다.
     * - 키 / 값 모두 String 직렬화기를 사용한다.
     *
     * @param factory  cacheConnectionFactory
     * @return         재사용 캐시 키 조작용 StringRedisTemplate
     */
    @Bean(name = "cacheRedisTemplate")
    public StringRedisTemplate cacheRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("cacheConnectionFactory")
            RedisConnectionFactory factory
    ) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 컨텍스트 종료 시 공유 ClientResources 를 정리한다.
     *
     * ConnectionFactory 는 외부에서 주입한 ClientResources 를 스스로 종료하지
     * 않으므로(소유권 비보유), 생성한 본 설정이 책임진다. Spring 은 빈을 역순으로
     * 소멸시키므로 각 팩토리(destroy)가 먼저 정리된 뒤 본 메서드가 실행된다.
     */
    @PreDestroy
    public void shutdownClientResources() {
        clientResources.shutdown();
    }
}
