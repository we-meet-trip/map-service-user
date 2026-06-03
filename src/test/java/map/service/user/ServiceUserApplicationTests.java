package map.service.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ServiceUserApplicationTests — 애플리케이션 컨텍스트 로딩 스모크 테스트
 *
 * 본 테스트는 SpringBootTest 어노테이션으로 전체 컨텍스트를 띄워서
 * 빈 구성에 누락 / 충돌이 없는지를 검증한다.
 *
 * 검증 범위:
 * - ServiceUserApplication 의 컴포넌트 스캔 결과로 모든 Configuration / Component 가
 *   문제없이 등록되는가
 * - RedisConfig / AgentClientConfig / StreamsConsumerConfig 가 정상 초기화되는가
 *
 * 비고:
 * - 별도 assert 없이 contextLoads() 가 예외 없이 종료되면 통과로 본다.
 */
@SpringBootTest
class ServiceUserApplicationTests {

    /**
     * Spring 컨텍스트 로딩 확인.
     *
     * 빈 구성에 오류가 있으면 컨텍스트 초기화 단계에서 예외가 발생하여 실패한다.
     */
    @Test
    void contextLoads() {
    }

}
