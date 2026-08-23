package map.service.user.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 일정의 한 방문지에 실제로 닿은 기록.
 *
 * <p>좌표를 담지 않는다. 어디였는지는 일정에 이미 적혀 있어, 그것과 이 시각을
 * 맞추면 필요한 것이 다 나온다. 위치 원점을 한 벌 더 두면 다루기가 무거워진다.
 */
@Entity
@IdClass(ScheduleArrivalId.class)
@Table(name = "schedule_arrivals", schema = "user_service")
public class ScheduleArrivalEntity {

    @Id
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Id
    @Column(name = "trip_day")
    private Integer day;

    @Id
    @Column(name = "stop_order")
    private Integer stopOrder;

    @Column(name = "arrived_at", nullable = false)
    private OffsetDateTime arrivedAt;

    /** 계획 시각이 확실한 일정이었는지. 확실치 않으면 시간차 비교가 뜻이 없다. */
    @Column(name = "timeline_status", length = 16)
    private String timelineStatus;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ScheduleArrivalEntity() {
    }

    public ScheduleArrivalEntity(Long scheduleId, Integer day, Integer stopOrder,
                                 OffsetDateTime arrivedAt, String timelineStatus) {
        this.scheduleId = scheduleId;
        this.day = day;
        this.stopOrder = stopOrder;
        this.arrivedAt = arrivedAt;
        this.timelineStatus = timelineStatus;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public Integer getDay() {
        return day;
    }

    public Integer getStopOrder() {
        return stopOrder;
    }

    public OffsetDateTime getArrivedAt() {
        return arrivedAt;
    }

    public String getTimelineStatus() {
        return timelineStatus;
    }
}
