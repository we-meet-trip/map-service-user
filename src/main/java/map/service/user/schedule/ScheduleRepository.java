package map.service.user.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ScheduleRepository — 일정 영속화 JPA 리포지토리
 *
 * ScheduleEntity 에 대한 기본 CRUD 를 JpaRepository 에서 상속받아 그대로 노출한다.
 * 식별자 타입은 Long(schedule_id).
 * ScheduleService.persist 가 save 메서드를 사용한다.
 */
public interface ScheduleRepository extends JpaRepository<ScheduleEntity, Long> {
}
