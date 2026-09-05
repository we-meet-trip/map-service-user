package map.service.user.schedule;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 방문지 도착 기록 저장소.
 *
 * <p>넣기만 하고 고치지 않는다. 처음 닿은 시각이 알고 싶은 것이고, 같은 자리를
 * 여러 번 알려 와도 뒤엣것은 버린다 — 기기가 위치를 받을 때마다 판정하기
 * 때문에 중복은 예외가 아니라 정상이다.
 *
 * <p>"이미 있으면 넘어가기" 는 서비스에서 처리한다. 한 문장으로 끝내는 방법
 * (ON CONFLICT)은 DB 마다 달라 시험이 쓰는 DB 에서 통째로 깨지고, 이 저장소는
 * 이미 "넣어 보고 부딪히면 삼킨다" 는 방식을 다른 곳에서도 쓰고 있다.
 */
@Repository
public interface ScheduleArrivalRepository
        extends JpaRepository<ScheduleArrivalEntity, ScheduleArrivalId> {

    /** 여러 일정의 도착 기록을 한 번에 읽는다(학습 자료 조립용). */
    List<ScheduleArrivalEntity> findByScheduleIdIn(Collection<Long> scheduleIds);
}
