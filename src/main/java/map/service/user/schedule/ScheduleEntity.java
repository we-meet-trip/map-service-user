package map.service.user.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ScheduleEntity — 일정 JPA 엔티티
 *
 * user_service 스키마의 schedules 테이블에 매핑된다.
 * 추천 draft 의 JSON 본문 전체를 payload 컬럼에 JSONB 로 보관해 스냅샷을 보존한다.
 * ScheduleService.persist 가 본 엔티티를 생성·저장한다.
 *
 * 필드:
 * - scheduleId: PK. @Id @GeneratedValue(IDENTITY). 컬럼명 schedule_id.
 * - userId: 소유자 식별자. 컬럼명 user_id.
 * - jobId: 원본 추천 작업 식별자 UUID. 컬럼명 job_id.
 * - title: 일정 제목.
 * - dateStart: 시작일. NOT NULL.
 * - dateEnd: 종료일. NOT NULL.
 * - payload: draft JSON 스냅샷. JSONB 컬럼 + @JdbcTypeCode(SqlTypes.JSON). NOT NULL.
 * - createdAt: 생성 시각. @CreationTimestamp 로 영속 시 채워져 save 직후에도
 *   조회 가능(updatable=false). DB DEFAULT now() 와도 정합.
 *
 * 아래 3개는 payload(추천 결과 원본)에 없는 화면 복원용 메타다. 방문 시각은
 * 활동 시간대를 균등 분할해 만들고 이동 카드/경로 프로파일은 이동수단으로
 * 정해지므로, 이 값이 없으면 상세 조회가 생성 직후 화면과 달라진다.
 * 메타 도입 이전에 저장된 행은 전부 null 이다.
 * - transport: 이동수단(walk|bicycle|scooter|bus).
 * - activeStartHour / activeEndHour: 하루 활동 시간대(0~24).
 */
@Entity
@Table(name = "schedules", schema = "user_service")
// 지운 흔적은 모든 JPA 조회에서 빠진다. 파생 쿼리마다 조건을 붙이는 방식이면
// findById 같은 기본 조회가 그대로 새는데, 실제로 채팅방을 만드는 쪽이 그것을
// 쓰고 있어 지운 일정으로 방이 열릴 수 있었다. 한 곳에서 막는다.
// (학습용 내보내기는 네이티브 SQL 로 직접 읽어 "지웠다" 를 신호로 쓴다.)
@SQLRestriction("deleted_at is null")
public class ScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "title")
    private String title;

    @Column(name = "date_start", nullable = false)
    private LocalDate dateStart;

    @Column(name = "date_end", nullable = false)
    private LocalDate dateEnd;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payload;

    @Column(name = "transport")
    private String transport;

    @Column(name = "active_start_hour")
    private Integer activeStartHour;

    @Column(name = "active_end_hour")
    private Integer activeEndHour;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * 이 일정을 처음 따라가기 시작한 시각. 시작한 적이 없으면 null.
     *
     * 두 번째 시작 요청은 이 값을 덮지 않는다 — 덮으면 "언제부터 이 일정을
     * 따라갔는가"를 잃고, 화면을 다시 열 때마다 시작 시각이 밀린다.
     */
    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    /**
     * 추천 당시의 광역시도/시군구. 이 일정의 날씨를 다시 물으려면 지역이
     * 있어야 한다 — payload 의 장소에는 좌표만 있고 hub 날씨는 지역명으로
     * 묻는다. 지역 도입 이전에 저장된 행은 둘 다 null 이고, 그런 일정은
     * 날씨 감시 대상에서 빠진다(추측해 채우지 않는다).
     */
    @Column(name = "province", length = 20)
    private String province;

    @Column(name = "city", length = 20)
    private String city;

    /**
     * 저장 시점에 굳혀 둔 날짜별 예보(WeatherSnapshotItem 목록의 JSON).
     * 나중 예보와 견주는 기준선이다. hub 가 답하지 못했으면 null.
     */
    @Column(name = "weather_baseline", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode weatherBaseline;

    /**
     * 지금 걸려 있는 날씨 변화 알림(WeatherAlert 의 JSON). 없으면 null.
     * 재추천을 시작하면 지운다 — 다시 짠 코스는 지금 예보를 이미 반영한다.
     */
    @Column(name = "weather_alert", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode weatherAlert;

    /** 마지막으로 예보를 다시 받아 견준 시각. 견준 적이 없으면 null. */
    @Column(name = "weather_checked_at")
    private OffsetDateTime weatherCheckedAt;
     /**
     * 사용자가 지운 시각. 지운 적 없으면 비어 있다.
     *
     * 행을 통째로 지우지 않는 이유: "저장했다가 물렀다" 는 사용자가 남기는
     * 가장 뚜렷한 부정 신호다. 지워 버리면 그 판단이 아무 데도 남지 않아,
     * 저장만 정답으로 쓰는 쪽은 "받아들였다" 만 배우게 된다.
     *
     * 대신 기한이 지나면 정리하는 쪽에서 진짜로 지운다.
     */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * JPA 요구사항을 위한 보호 수준 기본 생성자.
     */
    protected ScheduleEntity() {
    }

    /**
     * 도메인 생성자.
     *
     * scheduleId 는 PERSIST 시 DB 가 채워주므로 인자로 받지 않으며,
     * createdAt 역시 DB 기본값이라 인자에 없다.
     *
     * userId: 소유자 식별자(미지정 시 null).
     * jobId: 원본 추천 작업 UUID(@Pattern 검증을 거친 값).
     * title: 일정 제목.
     * dateStart: 시작일.
     * dateEnd: 종료일.
     * payload: draft JSON 스냅샷.
     * transport: 이동수단(미지정 시 null).
     * activeStartHour / activeEndHour: 활동 시간대(미지정 시 null).
     */
    public ScheduleEntity(
            Long userId,
            UUID jobId,
            String title,
            LocalDate dateStart,
            LocalDate dateEnd,
            JsonNode payload,
            String transport,
            Integer activeStartHour,
            Integer activeEndHour
    ) {
        this.userId = userId;
        this.jobId = jobId;
        this.title = title;
        this.dateStart = dateStart;
        this.dateEnd = dateEnd;
        this.payload = payload;
        this.transport = transport;
        this.activeStartHour = activeStartHour;
        this.activeEndHour = activeEndHour;
    }

    /**
     * scheduleId 반환. save 호출 전에는 null.
     */
    public Long getScheduleId() {
        return scheduleId;
    }

    /**
     * 소유자 식별자 반환. 미지정(익명/토큰 부재) 시 null.
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * jobId UUID 반환(@Pattern 검증된 값).
     */
    public UUID getJobId() {
        return jobId;
    }

    /**
     * 일정 제목 반환. 미지정 시 null.
     */
    public String getTitle() {
        return title;
    }

    /**
     * 시작일 반환.
     */
    public LocalDate getDateStart() {
        return dateStart;
    }

    /**
     * 종료일 반환.
     */
    public LocalDate getDateEnd() {
        return dateEnd;
    }

    /**
     * draft JSON 스냅샷 반환.
     */
    public JsonNode getPayload() {
        return payload;
    }

    /**
     * 이동수단 반환. 메타 도입 이전 행이거나 미지정이면 null.
     */
    public String getTransport() {
        return transport;
    }

    /**
     * 활동 시작 시각 반환. 메타 도입 이전 행이거나 미지정이면 null.
     */
    public Integer getActiveStartHour() {
        return activeStartHour;
    }

    /**
     * 활동 종료 시각 반환. 메타 도입 이전 행이거나 미지정이면 null.
     */
    public Integer getActiveEndHour() {
        return activeEndHour;
    }

    /**
     * 처음 시작한 시각 반환. 시작한 적이 없으면 null.
     */
    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    /**
     * 아직 시작한 적이 없을 때만 시작 시각을 새긴다.
     *
     * 이미 값이 있으면 그대로 둔다. 같은 일정을 다시 열 때마다 값을 덮으면
     * 시작 시각이 매번 밀려 "언제부터 따라갔는가"가 남지 않는다.
     *
     * @return 이번 호출로 새로 새겼으면 true
     */
    public boolean markStarted(OffsetDateTime at) {
        if (startedAt != null) {
            return false;
        }
        startedAt = at;
        return true;
    }

    /**
     * 방문지를 통째로 갈아 끼운다 — 사용자가 일정을 고쳐 다시 만든 경우다.
     *
     * 방문지를 하나씩 고치지 않고 통째로 바꾸는 이유는, 스냅샷 안의 방문
     * 순서·이동 구간·시각이 서로 맞물려 있어 한 자리만 바꾸면 나머지가
     * 어긋나기 때문이다. 고친 목록으로 동선을 새로 짠 결과가 들어온다.
     *
     * 제목·날짜·소유자·지역·예보 기준선은 건드리지 않는다. 장소를 더하고
     * 빼고 순서를 바꾸는 일로는 그 값들이 달라지지 않는다.
     *
     * jobId: 새로 만든 일정의 작업 식별자.
     * payload: 새 draft JSON 스냅샷(호출 측이 이미 봉한 값).
     * transport / activeStartHour / activeEndHour: null 이면 기존 값을 둔다.
     */
    public void replaceItinerary(
            UUID jobId,
            JsonNode payload,
            String transport,
            Integer activeStartHour,
            Integer activeEndHour
    ) {
        this.jobId = jobId;
        this.payload = payload;
        if (transport != null) {
            this.transport = transport;
        }
        if (activeStartHour != null) {
            this.activeStartHour = activeStartHour;
        }
        if (activeEndHour != null) {
            this.activeEndHour = activeEndHour;
        }
    }

    /**
     * 생성 시각 반환. @CreationTimestamp 로 채워진 값(save 직후에도 non-null).
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** 추천 당시 광역시도. 지역을 모르는 행이면 null. */
    public String getProvince() {
        return province;
    }

    /** 추천 당시 시군구. 지역을 모르는 행이면 null. */
    public String getCity() {
        return city;
    }

    /**
     * 지역을 새긴다. 둘 중 하나라도 비면 둘 다 비운다 — 반쪽 지역으로는
     * hub 에 날씨를 물을 수 없어 남겨 두면 감시가 매번 실패한다.
     */
    public void setRegion(String province, String city) {
        boolean usable = province != null && !province.isBlank()
                && city != null && !city.isBlank();
        this.province = usable ? province : null;
        this.city = usable ? city : null;
    }

    /** 저장 시점 예보 기준선 반환. 없으면 null. */
    public JsonNode getWeatherBaseline() {
        return weatherBaseline;
    }

    public void setWeatherBaseline(JsonNode weatherBaseline) {
        this.weatherBaseline = weatherBaseline;
    }

    /** 지금 걸려 있는 날씨 변화 알림 반환. 없으면 null. */
    public JsonNode getWeatherAlert() {
        return weatherAlert;
    }

    public void setWeatherAlert(JsonNode weatherAlert) {
        this.weatherAlert = weatherAlert;
    }

    /** 마지막으로 예보를 견준 시각 반환. 견준 적 없으면 null. */
    public OffsetDateTime getWeatherCheckedAt() {
        return weatherCheckedAt;
    }

    public void setWeatherCheckedAt(OffsetDateTime weatherCheckedAt) {
        this.weatherCheckedAt = weatherCheckedAt;
    }

    /** 지운 시각 반환. 살아 있으면 비어 있다. */
    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    /**
     * 지운 것으로 표시한다. 이미 지운 것은 그대로 둔다.
     *
     * 처음 지운 시각을 지키는 이유: 기한이 지나면 정리하는 쪽이 이 시각을
     * 기준으로 삼는다. 다시 지울 때마다 시각을 덮으면 정리가 계속 미뤄진다.
     */
    /** Keep only the hidden row needed by shared chat foreign keys; no deleted itinerary for learning. */
    public void eraseItinerary() {
        jobId = null;
        title = null;
        payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        province = null;
        city = null;
        transport = null;
        activeStartHour = null;
        activeEndHour = null;
        startedAt = null;
        weatherBaseline = null;
        weatherAlert = null;
        weatherCheckedAt = null;
    }

    public void markDeleted(OffsetDateTime at) {
        if (deletedAt == null) {
            deletedAt = at;
        }
    }
}
