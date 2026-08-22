package map.service.user.recommend;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * RecommendJobRepository — recommend_jobs 테이블 Spring Data JPA 저장소
 *
 * RecommendJobStore 가 본 저장소로 추천 작업 상태/결과를 조회·영속화한다.
 * 식별자 타입은 UUID(job_id)이다. 관리자 통계/목록용 쿼리를 추가한다.
 */
public interface RecommendJobRepository extends JpaRepository<RecommendJobEntity, UUID> {

    /** 상태별 건수 집계: [status, count] 행 목록(운영 통계). */
    @Query("SELECT r.status AS status, COUNT(r) AS cnt "
            + "FROM RecommendJobEntity r GROUP BY r.status")
    List<Object[]> countGroupByStatus();

    /** 특정 상태이면서 createdAt 이 기준 시각 이후인 건수(최근 실패수 등). */
    long countByStatusAndCreatedAtAfter(String status, OffsetDateTime since);

    /** 상태별 페이지 조회(목록). status 미지정 시 findAll(Pageable) 사용. */
    Page<RecommendJobEntity> findByStatus(String status, Pageable pageable);

    /**
     * 비어 있는 출처 정보만 채운다. 이미 값이 있는 칸과 status 는 건드리지 않는다.
     *
     * status 를 갱신 대상에서 뺀 것이 핵심이다. 완료 이벤트가 접수보다 먼저
     * 도착할 수 있는데, 그때 status 를 덮으면 끝난 잡이 진행 중으로 되돌아가
     * 조회하는 쪽이 영원히 기다린다. 그 문제는 예전에 "이미 있으면 아무 것도
     * 하지 않기" 로 막았는데, 그러면 이번에는 완료가 먼저 온 잡의 mode·source 가
     * 영영 비어 있게 된다. 둘 다 피하려면 status 는 두고 빈 칸만 채워야 한다.
     *
     * COALESCE 는 이미 값이 있는 칸을 지킨다. 같은 잡에 접수 기록이 두 번
     * 들어와도 처음 것이 남는다. 문장 하나이므로 행 단위로 원자적이다.
     *
     * @return 갱신된 행 수. 0 이면 그 잡의 행이 아직 없다는 뜻이다.
     */
    @Modifying
    @Query(value = """
            UPDATE user_service.recommend_jobs SET
                schedule_id   = COALESCE(schedule_id,   :scheduleId),
                mode          = COALESCE(mode,          :mode),
                parent_job_id = COALESCE(parent_job_id, :parentJobId),
                source        = COALESCE(source,        :source),
                owner_user_id = COALESCE(owner_user_id, :ownerUserId)
            WHERE job_id = :jobId
            """, nativeQuery = true)
    int fillOriginIfAbsent(@Param("jobId") UUID jobId,
                           @Param("scheduleId") String scheduleId,
                           @Param("mode") String mode,
                           @Param("parentJobId") UUID parentJobId,
                           @Param("source") String source,
                           @Param("ownerUserId") Long ownerUserId);
}
