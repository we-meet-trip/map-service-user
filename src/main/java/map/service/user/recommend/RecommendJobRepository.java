package map.service.user.recommend;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
