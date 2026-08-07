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
 * ReviewSummaryPrewarmConfig — 장소 요약 선작성용 Executor 빈 설정
 *
 * 일정이 만들어진 직후 그 일정의 장소 요약을 미리 만들어 두는 작업을 응답
 * 스레드 밖에서 돌린다. 응답을 기다리는 사용자가 이 작업 때문에 늦어지면
 * 안 되므로 제출만 하고 즉시 돌아간다.
 *
 * 스레드는 1개다. 작업 한 건이 모델 호출 1회를 쓰는데 모델의 분당 호출
 * 상한이 낮아, 스레드를 늘려도 모델 앞에서 줄을 서느라 대기만 길어진다.
 * 1개로 두면 선작성이 사용자가 장소를 눌렀을 때의 요약 요청보다 앞서
 * 모델 용량을 차지하는 일도 생기지 않는다.
 *
 * 캐시 갱신용 스레드풀과 공유하지 않는다. 성격이 다른 두 작업이 한 풀을
 * 쓰면 한쪽이 몰릴 때 다른 쪽이 굶는다.
 *
 * 대기열이 가득 차면 초과 작업은 경고만 남기고 버린다 — 선작성은 어디까지나
 * 미리 해 두는 최적화이고, 버려져도 장소를 누르는 시점에 그 자리에서
 * 만들어지므로 화면은 정상 동작한다.
 */
@Configuration
public class ReviewSummaryPrewarmConfig {

    private static final Logger log =
            LoggerFactory.getLogger(ReviewSummaryPrewarmConfig.class);

    private ThreadPoolExecutor executor;

    /**
     * 요약 선작성용 고정 스레드풀 빈.
     *
     * 사용처: ReviewSummaryService 가
     *        Qualifier("reviewSummaryPrewarmExecutor") 로 주입받는다.
     *
     * poolSize: reviews.prewarm-pool-size 프로퍼티(기본 1).
     * queueCapacity: reviews.prewarm-queue-capacity 프로퍼티(기본 50).
     */
    @Bean(name = "reviewSummaryPrewarmExecutor")
    public Executor reviewSummaryPrewarmExecutor(
            @Value("${reviews.prewarm-pool-size:1}") int poolSize,
            @Value("${reviews.prewarm-queue-capacity:50}") int queueCapacity
    ) {
        this.executor = new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                (task, exec) -> log.warn(
                        "review summary prewarm queue full (capacity={}),"
                                + " discarding task",
                        queueCapacity)
        );
        return executor;
    }

    /** 컨텍스트 종료 시 스레드풀을 정리한다. */
    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
