package map.service.user.schedule;

import java.io.Serializable;
import java.util.Objects;

/**
 * 도착 기록의 열쇠 — (일정, 일차, 순번).
 *
 * <p>순번까지 있어야 같은 날 여러 곳을 구분한다. 일차를 함께 두는 이유는
 * 순번이 일차마다 다시 시작하기 때문이다.
 */
public class ScheduleArrivalId implements Serializable {

    private Long scheduleId;
    private Integer day;
    private Integer stopOrder;

    protected ScheduleArrivalId() {
    }

    public ScheduleArrivalId(Long scheduleId, Integer day, Integer stopOrder) {
        this.scheduleId = scheduleId;
        this.day = day;
        this.stopOrder = stopOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScheduleArrivalId other)) {
            return false;
        }
        return Objects.equals(scheduleId, other.scheduleId)
                && Objects.equals(day, other.day)
                && Objects.equals(stopOrder, other.stopOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheduleId, day, stopOrder);
    }
}
