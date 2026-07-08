package map.service.user.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RecommendCacheConfig — 재사용 캐시 백그라운드 갱신용 Executor 빈 설정
 *
 * JDK 17(virtual thread 미사용, build.gradle 참조)에서 fire-and-forget 백그라운드
 * 작업을 처리하기 위한 고정 크기 스레드풀을 등록한다. RecommendService 가 히트
 * 카운터의 배수 시점에 이 Executor 로 agent 재호출을 예약한다(응답 스레드와 무관).
 *
 * 종료 시 @PreDestroy 로 스레드풀을 정리한다.
 */
@Configuration
public class RecommendCacheConfig {

    private ExecutorService executor;

    /**
     * 백그라운드 캐시 갱신용 고정 스레드풀(2 스레드) 빈.
     *
     * 사용처: RecommendService 가 Qualifier("recommendCacheRefreshExecutor") 로
     *        주입받아 agent 재호출을 fire-and-forget 으로 예약한다.
     */
    @Bean(name = "recommendCacheRefreshExecutor")
    public Executor recommendCacheRefreshExecutor() {
        this.executor = Executors.newFixedThreadPool(2);
        return executor;
    }

    /**
     * 컨텍스트 종료 시 스레드풀을 정리한다.
     */
    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
