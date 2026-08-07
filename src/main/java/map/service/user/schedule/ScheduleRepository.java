package map.service.user.schedule;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ScheduleRepository — 일정 영속화 JPA 리포지토리
 *
 * ScheduleEntity 에 대한 기본 CRUD 를 JpaRepository 에서 상속받아 그대로 노출한다.
 * 식별자 타입은 Long(schedule_id).
 *
 * 조회 메서드는 전부 소유자 조건을 파라미터로 받는다. 식별자만으로 찾는
 * findById 를 쓰면 남의 일정이 그대로 열리므로, 서비스 계층이 소유자 조건을
 * 빠뜨릴 수 없도록 시그니처 자체에 박아 둔다. 소유자가 없는(토큰 없이 저장된)
 * 행을 찾는 메서드는 두지 않는다 — user_id IS NULL 로 묶으면 서로 다른
 * 사용자의 일정이 한 덩어리가 되어 누구에게나 열린다.
 */
public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {

    /** 소유자의 일정 목록. 시작일 오름차순 — 다가오는 여행이 위로 온다. */
    List<ScheduleEntity> findByUserIdOrderByDateStartAsc(Long userId);

    /** 소유자의 일정 1건. 남의 일정이면 비어 있다. */
    Optional<ScheduleEntity> findByScheduleIdAndUserId(Long scheduleId, Long userId);
}
