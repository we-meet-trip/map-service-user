package map.service.user.recommend;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RecommendTrainingRepository — recommend_training 저장소
 *
 * 잡 하나에 신호 한 건이므로 식별자는 job_id 이고 별도 조회 메서드가 없다.
 * 쓰기는 완료 이벤트 소비 경로에서만 일어난다.
 */
public interface RecommendTrainingRepository extends JpaRepository<RecommendTrainingEntity, UUID> {
}
