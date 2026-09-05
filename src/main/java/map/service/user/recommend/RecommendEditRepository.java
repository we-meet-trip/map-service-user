package map.service.user.recommend;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * RecommendEditRepository — recommend_edits 저장소
 *
 * 초안 수정 전후 기록을 넣고, 재시도로 같은 요청이 두 번 들어왔는지 확인한다.
 */
public interface RecommendEditRepository extends JpaRepository<RecommendEditEntity, Long> {

    /** 같은 요청이 이미 처리됐는지 본다(재시도 판정). */
    Optional<RecommendEditEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * 이 잡의 다음 순번. 아직 없으면 1.
     *
     * 조회와 삽입 사이에 다른 수정이 끼어들 수 있으므로 이 값만으로는 유일성을
     * 보장하지 못한다. (job_id, seq) 유일 제약이 최종 판정을 하고, 호출부가
     * 충돌을 받아 한 번 다시 시도한다.
     */
    @Query("SELECT COALESCE(MAX(e.seq), 0) + 1 FROM RecommendEditEntity e WHERE e.jobId = :jobId")
    int nextSeq(@Param("jobId") UUID jobId);

    /**
     * 기준 시각보다 오래된 기록을 한 번에 limit 건까지 지운다.
     *
     * 전후를 통째로 담아 한 행이 크므로, 신호보다 더 나눠 지울 이유가 있다.
     *
     * @return 지운 행 수.
     */
    @Modifying
    @Query(value = """
            DELETE FROM user_service.recommend_edits
            WHERE id IN (
                SELECT id FROM user_service.recommend_edits
                WHERE created_at < :cutoff
                ORDER BY created_at
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
