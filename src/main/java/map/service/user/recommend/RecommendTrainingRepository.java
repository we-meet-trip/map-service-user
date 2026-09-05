package map.service.user.recommend;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * RecommendTrainingRepository — recommend_training 저장소
 *
 * 잡 하나에 신호 한 건이므로 식별자는 job_id 이고 별도 조회 메서드가 없다.
 * 쓰기는 완료 이벤트 소비 경로에서만 일어난다.
 */
public interface RecommendTrainingRepository extends JpaRepository<RecommendTrainingEntity, UUID> {

    /**
     * 기준 시각보다 오래된 신호를 한 번에 limit 건까지 지운다.
     *
     * 건수를 끊는 이유: 쌓인 것을 한 문장으로 지우면 그 트랜잭션이 오래 잠금을
     * 쥔다. 나눠 지우면 한 번이 짧게 끝나고, 남은 것은 다음 주기가 이어서 한다.
     *
     * @return 지운 행 수.
     */
    @Modifying
    @Query(value = """
            DELETE FROM user_service.recommend_training
            WHERE job_id IN (
                SELECT job_id FROM user_service.recommend_training
                WHERE created_at < :cutoff
                ORDER BY created_at
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
