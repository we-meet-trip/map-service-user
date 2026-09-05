package map.service.user.nearby;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 주변 장소를 한 건 보여 준 기록과, 그것이 눌렸는지.
 *
 * <p>보여 준 것을 함께 남기는 이유: 눌린 것만 남기면 "안 눌렀다" 가
 * "안 보였다" 인지 "보고 안 골랐다" 인지 구분되지 않는다. 반례가 성립하려면
 * 보여 준 목록이 있어야 한다.
 *
 * <p>좌표를 담지 않는다. 어느 방문지 주변인지는 (일차, 순번) 으로 지목되고,
 * 그 자리는 일정에 이미 적혀 있다.
 */
@Entity
@Table(name = "nearby_impressions", schema = "user_service")
public class NearbyImpressionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "trip_day", nullable = false)
    private Integer day;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    /** stay | food | cafe. 발급처 분류 코드가 아니라 우리 말로 담는다. */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    @Column(name = "content_id", nullable = false, length = 64)
    private String contentId;

    /** 목록에서 몇 번째로 보였는지. 노출 편향을 보정하는 데 쓴다. */
    @Column(name = "rank", nullable = false)
    private Integer rank;

    @Column(name = "shown_at", nullable = false)
    private OffsetDateTime shownAt;

    /** 누른 적 없으면 비어 있다. */
    @Column(name = "clicked_at")
    private OffsetDateTime clickedAt;

    protected NearbyImpressionEntity() {
    }

    public NearbyImpressionEntity(Long scheduleId, Integer day, Integer stopOrder,
                                  String category, String contentId, Integer rank,
                                  OffsetDateTime shownAt) {
        this.scheduleId = scheduleId;
        this.day = day;
        this.stopOrder = stopOrder;
        this.category = category;
        this.contentId = contentId;
        this.rank = rank;
        this.shownAt = shownAt;
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

    public String getCategory() {
        return category;
    }

    public String getContentId() {
        return contentId;
    }

    public Integer getRank() {
        return rank;
    }

    public OffsetDateTime getShownAt() {
        return shownAt;
    }

    public OffsetDateTime getClickedAt() {
        return clickedAt;
    }

    /**
     * 눌린 것으로 새긴다. 이미 눌렀으면 처음 시각을 지킨다.
     *
     * <p>같은 것을 여러 번 눌러도 알고 싶은 것은 "골랐다" 는 사실이지 몇 번
     * 눌렀는지가 아니다. 덮으면 처음 눈길이 간 때가 사라진다.
     */
    public void markClicked(OffsetDateTime at) {
        if (clickedAt == null) {
            clickedAt = at;
        }
    }
}
