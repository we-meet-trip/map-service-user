package map.service.user.recommend;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RecommendJobRepository — recommend_jobs 테이블 Spring Data JPA 저장소
 *
 * RecommendJobStore 가 본 저장소로 추천 작업 상태/결과를 조회·영속화한다.
 * 식별자 타입은 UUID(job_id)이다.
 */
public interface RecommendJobRepository extends JpaRepository<RecommendJobEntity, UUID> {
}
