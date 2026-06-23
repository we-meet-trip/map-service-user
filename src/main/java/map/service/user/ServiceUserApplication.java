package map.service.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;

/**
 * ServiceUserApplication — user-BFF 서비스의 부트 진입점
 *
 * 본 클래스는 Spring Boot 애플리케이션의 메인 클래스이며,
 * 컴포넌트 스캔의 기준 패키지(map.service.user)를 결정한다.
 *
 * 자동 구성 제외:
 * - RedisRepositoriesAutoConfiguration 을 명시적으로 제외한다.
 *   본 서비스는 JPA Repository(예: ScheduleRepository)를 사용하는데,
 *   Spring Data Redis 의 자동 구성이 redisReferenceResolver 를 등록하면서
 *   JPA Repository 와 충돌하는 사례가 있어 이를 회피하기 위함이다.
 *   (application.yml 에서도 spring.data.redis.repositories.enabled=false 로
 *    Repository 자동 활성화를 끄고 있다.)
 *
 * 실행 흐름:
 * - main() 은 SpringApplication.run() 으로 컨텍스트를 기동한다.
 * - 기동 후 RedisConfig / AgentClientConfig / StreamsConsumerConfig 가
 *   각각 RestClient · ConnectionFactory · StreamMessageListenerContainer 를
 *   빈으로 등록한다.
 */
@SpringBootApplication(exclude = {RedisRepositoriesAutoConfiguration.class})
public class ServiceUserApplication {

    /**
     * Spring Boot 애플리케이션을 기동한다.
     *
     * @param args  실행 시 전달된 커맨드라인 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(ServiceUserApplication.class, args);
    }

}
