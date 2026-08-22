package map.service.user.recommend;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * TrainingSignalSweeper — 오래된 학습 신호와 편집 기록을 지우는 주기 작업
 *
 * 두 표는 요청이 들어올 때마다 늘어나기만 하고 줄어들 일이 없다. 하루 2,000건을
 * 받으면 신호만 연 1GB 대로 불어나고, 편집 기록은 전후를 통째로 담아 더 크다.
 * 그대로 두면 잡 상태를 보는 조회까지 함께 느려진다.
 *
 * 얼마나 두는가:
 *   기본 180일. 랭킹을 손볼 때 최근 몇 달을 쓰면 충분하고, 그보다 오래된 것은
 *   장소도 사용자 취향도 달라져 있어 학습에 넣으면 오히려 해가 된다.
 *
 * 개인정보 관점:
 *   신호에는 어디를 가려 했는지가 담긴다. 무기한 보관은 그 자체로 위험이므로
 *   기한을 두는 것이 맞다.
 *
 * 한 번에 지우는 양을 끊는 이유:
 *   쌓인 것을 한 문장으로 지우면 그 트랜잭션이 오래 잠금을 쥔다. 나눠 지우면
 *   한 번이 짧게 끝나고, 다 지우지 못해도 다음 주기가 이어서 한다.
 *
 * 실행 간격은 recommend.retention-sweep-interval-ms(기본 6시간)로 설정한다.
 * 지우는 일이 급하지 않아 자주 돌 이유가 없다.
 */
@Component
public class TrainingSignalSweeper {

    private static final Logger log = LoggerFactory.getLogger(TrainingSignalSweeper.class);

    private final RecommendTrainingRepository trainingRepository;
    private final RecommendEditRepository editRepository;
    private final int retentionDays;
    private final int batchSize;

    public TrainingSignalSweeper(
            RecommendTrainingRepository trainingRepository,
            RecommendEditRepository editRepository,
            @Value("${recommend.retention-days:180}") int retentionDays,
            @Value("${recommend.retention-batch:1000}") int batchSize) {
        this.trainingRepository = trainingRepository;
        this.editRepository = editRepository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${recommend.retention-sweep-interval-ms:21600000}",
            initialDelayString = "${recommend.retention-sweep-interval-ms:21600000}")
    @Transactional
    public void sweep() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        try {
            int signals = trainingRepository.deleteOlderThan(cutoff, batchSize);
            int edits = editRepository.deleteOlderThan(cutoff, batchSize);
            if (signals > 0 || edits > 0) {
                log.info("training retention swept signals={} edits={} cutoff={}",
                        signals, edits, cutoff);
            }
        } catch (RuntimeException e) {
            // 정리에 실패해도 서비스는 계속 돌아야 한다. 다음 주기가 다시 한다.
            log.warn("training retention sweep failed reason={}", e.getMessage());
        }
    }
}
