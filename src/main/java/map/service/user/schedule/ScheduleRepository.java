package map.service.user.schedule;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 소유자의 일정 전부 삭제.
     *
     * 탈퇴 처리에서만 부른다. schedules 는 users 를 외래키로 걸지 않아 사용자 행을
     * 지워도 함께 사라지지 않는다 — 남겨 두면 주인 없는 일정이 되고, 그 일정에 걸린
     * 채팅방도 같이 남는다. 방은 schedule_id 외래키가 ON DELETE CASCADE 이므로
     * 이 삭제로 함께 정리된다.
     */
    void deleteByUserId(Long userId);

    /**
     * 날씨를 계속 지켜볼 일정들.
     *
     * 아직 끝나지 않았고(종료일이 오늘 이후), 지역과 기준선을 둘 다 가진 행만
     * 고른다. 지나간 일정은 다시 짤 수 없고, 지역·기준선이 없으면 견줄 수
     * 없어 매번 헛되이 hub 를 부르게 된다. 소유자 조건이 없는 유일한 조회이며
     * 서버 내부 배치 전용이다 — 결과를 사용자에게 그대로 내보내지 않는다.
     */
    @Query("""
            select s from ScheduleEntity s
            where s.dateEnd >= :today
              and s.province is not null
              and s.city is not null
              and s.weatherBaseline is not null
            order by s.dateStart asc
            """)
    List<ScheduleEntity> findWatchTargets(@Param("today") LocalDate today);
     /**
     * 기한이 지난 흔적을 실제로 지운다. 지운 행 수를 돌려준다.
     *
     * 흔적만 남긴 행은 조회에서 빠져 사용자 눈에는 이미 없지만 저장소에는
     * 남아 있다. 사용자가 지운 것을 무기한 들고 있지 않으려면 언젠가는 진짜로
     * 지워야 한다. 이때 딸린 채팅방·도착 기록도 외래키를 타고 함께 정리된다.
     *
     * 한 번에 지우는 양을 끊는 이유는 학습 신호 정리와 같다 — 한 문장으로
     * 몰아 지우면 그 트랜잭션이 오래 잠금을 쥔다.
     *
     * 엔티티에 걸린 조회 제한을 피해야 하므로 네이티브 문장으로 쓴다.
     */
    @Modifying
    @Query(value = """
            DELETE FROM user_service.schedules
            WHERE schedule_id IN (
                SELECT schedule_id FROM user_service.schedules
                WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff
                ORDER BY deleted_at
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteTombstonedBefore(@Param("cutoff") OffsetDateTime cutoff,
                               @Param("batchSize") int batchSize);
}
