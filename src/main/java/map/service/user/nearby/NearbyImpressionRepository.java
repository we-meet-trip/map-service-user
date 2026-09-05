package map.service.user.nearby;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 주변 장소 노출·클릭 기록 저장소. */
public interface NearbyImpressionRepository
        extends JpaRepository<NearbyImpressionEntity, Long> {

    Optional<NearbyImpressionEntity>
            findByScheduleIdAndDayAndStopOrderAndCategoryAndContentId(
                    Long scheduleId, Integer day, Integer stopOrder,
                    String category, String contentId);

    /** 학습 자료를 뽑을 때 일정 단위로 읽는다. */
    List<NearbyImpressionEntity> findByScheduleIdIn(Collection<Long> scheduleIds);

    /**
     * 기한이 지난 기록을 지운다. 지운 행 수를 돌려준다.
     *
     * <p>보여 준 것까지 남기므로 다른 표보다 빨리 불어난다. 한 번에 지우는 양을
     * 끊는 이유는 다른 정리와 같다 — 한 문장으로 몰아 지우면 그 트랜잭션이
     * 오래 잠금을 쥔다.
     */
    @Modifying
    @Query(value = """
            DELETE FROM user_service.nearby_impressions
            WHERE id IN (
                SELECT id FROM user_service.nearby_impressions
                WHERE shown_at < :cutoff
                ORDER BY shown_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff,
                        @Param("batchSize") int batchSize);
}
