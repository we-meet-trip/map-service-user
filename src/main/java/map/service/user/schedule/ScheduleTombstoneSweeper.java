package map.service.user.schedule;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기한이 지난 "지운 일정" 을 실제로 지우는 주기 작업.
 *
 * 왜 필요한가:
 *   일정을 지우면 행을 없애지 않고 지운 표시만 남긴다. "저장했다가 물렀다" 가
 *   학습에서 값진 신호이기 때문이다. 그런데 그대로 두면 사용자가 지운 것을
 *   저장소가 무기한 들고 있게 된다 — 지웠다는 말이 사실이 아니게 된다.
 *
 * 얼마나 두는가:
 *   기본 90일. 학습 신호를 두는 기간(180일)보다 짧게 잡았다. 지운 것은
 *   사용자가 이미 거둬들인 것이라 더 조심스럽게 다룬다.
 *
 * 무엇이 함께 사라지는가:
 *   행이 실제로 지워질 때 그 일정에 딸린 채팅방·참가자·주고받은 말이 외래키를
 *   타고 함께 정리된다. 흔적으로 바꾸면서 멈춰 있던 그 정리가 여기서 다시
 *   일어난다 — 지우는 길이 아주 없어지지는 않게 하려는 것이다.
 *
 * 한 번에 지우는 양을 끊는 이유와 실패해도 넘어가는 이유는 학습 신호 정리와
 * 같다. 급한 일이 아니라 다음 주기가 이어서 하면 된다.
 */
@Component
public class ScheduleTombstoneSweeper {

    private static final Logger log =
            LoggerFactory.getLogger(ScheduleTombstoneSweeper.class);

    private final ScheduleRepository repository;
    private final int retentionDays;
    private final int batchSize;

    public ScheduleTombstoneSweeper(
            ScheduleRepository repository,
            @Value("${schedule.tombstone-retention-days:90}") int retentionDays,
            @Value("${schedule.tombstone-retention-batch:500}") int batchSize) {
        this.repository = repository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${schedule.tombstone-sweep-interval-ms:21600000}",
            initialDelayString = "${schedule.tombstone-sweep-interval-ms:21600000}")
    @Transactional
    public void sweep() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        try {
            int purged = repository.deleteTombstonedBefore(cutoff, batchSize);
            if (purged > 0) {
                log.info("schedule tombstones purged count={} cutoff={}", purged, cutoff);
            }
        } catch (RuntimeException e) {
            log.warn("schedule tombstone sweep failed reason={}", e.getMessage());
        }
    }
}
