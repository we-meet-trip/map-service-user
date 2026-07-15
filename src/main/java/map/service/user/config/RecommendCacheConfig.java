package map.service.user.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RecommendCacheConfig — 재사용 캐시 백그라운드 갱신용 Executor 빈 설정
 *
 * JDK 17(virtual thread 미사용, build.gradle 참조)에서 fire-and-forget 백그라운드
 * 작업을 처리하기 위한 고정 크기 스레드풀을 등록한다. RecommendService 가 히트
 * 카운터의 배수 시점에 이 Executor 로 agent 재호출을 예약한다(응답 스레드와 무관).
 *
 * agent 장애/지연으로 작업이 처리 속도보다 빠르게 쌓이는 것을 막기 위해 대기열을
 * 유한 크기로 제한한다. 대기열이 가득 차면 초과 작업은 경고 로그만 남기고 버린다
 * (백그라운드 갱신은 실패해도 다음 히트 시점에 재시도되는 최적화 계층이므로 안전).
 *
 * 종료 시 @PreDestroy 로 스레드풀을 정리한다.
 *
 * queueCapacity: recommend.cache-refresh-queue-capacity 프로퍼티(기본 100).
 */
@Configuration
public class RecommendCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(RecommendCacheConfig.class);

    private ThreadPoolExecutor executor;

    /**
     * 백그라운드 캐시 갱신용 고정 스레드풀(2 스레드, 유한 대기열) 빈.
     *
     * 사용처: RecommendService 가 Qualifier("recommendCacheRefreshExecutor") 로
     *        주입받아 agent 재호출을 fire-and-forget 으로 예약한다.
     */
    @Bean(name = "recommendCacheRefreshExecutor")
    public Executor recommendCacheRefreshExecutor(
            @Value("${recommend.cache-refresh-queue-capacity:100}") int queueCapacity
    ) {
        this.executor = new ThreadPoolExecutor(
                2, 2,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                (task, exec) -> log.warn(
                        "recommend cache refresh queue full (capacity={}), discarding task",
                        queueCapacity)
        );
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
