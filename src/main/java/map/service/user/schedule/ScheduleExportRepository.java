package map.service.user.schedule;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 학습 자료를 뽑을 때만 쓰는 일정 조회.
 *
 * <p>왜 {@link ScheduleRepository} 에 두지 않는가: 그쪽은 "소유자 조건 없는
 * 조회를 두지 않는다" 를 규약으로 삼는다. 여기 있는 것은 남의 일정까지, 게다가
 * 지운 것까지 통째로 긁는다 — 요청을 처리하는 서비스가 실수로 집으면 그대로
 * 정보 유출이 된다. 이름과 자리를 갈라 두어 잘못 쓰기 어렵게 한다.
 *
 * <p>왜 네이티브인가: 엔티티에 "지운 것은 안 보인다" 는 조건이 걸려 있어 보통
 * 조회로는 흔적을 읽을 수 없다. 그런데 지웠다는 사실 자체가 학습에 필요한
 * 신호라, 그것만 넘어서 읽어야 한다.
 *
 * <p>문장에 JSON 연산자를 쓰지 않는다. 행만 집어 오고 payload 해체는 전부
 * 자바에서 한다 — DB 마다 다른 구문을 쓰면 시험이 쓰는 DB 에서 통째로 깨진다.
 */
public interface ScheduleExportRepository extends Repository<ScheduleEntity, Long> {

    /**
     * 식별자 순으로 한 쪽씩 읽는다. 지운 흔적도 함께 온다.
     *
     * <p>쪽을 번호가 아니라 "마지막으로 본 식별자" 로 넘기는 이유: 뽑는 도중에도
     * 새 일정이 들어오는데, 번호로 세면 그때마다 경계가 밀려 어떤 행은 두 번
     * 나오고 어떤 행은 건너뛴다.
     *
     * @param afterId 이 값보다 큰 것부터. 처음에는 0.
     * @param limit   한 번에 가져올 수
     */
    @Query(value = """
            SELECT * FROM user_service.schedules
            WHERE job_id IS NOT NULL AND schedule_id > :afterId
            ORDER BY schedule_id
            LIMIT :limit
            """, nativeQuery = true)
    List<ScheduleEntity> findPage(@Param("afterId") long afterId,
                                  @Param("limit") int limit);
}
