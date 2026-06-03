package map.service.user.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 */
@Entity
@Table(name = "schedules", schema = "user_service")
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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

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
     */
    public ScheduleEntity(
            Long userId,
            UUID jobId,
            String title,
            LocalDate dateStart,
            LocalDate dateEnd,
            JsonNode payload
    ) {
        this.userId = userId;
        this.jobId = jobId;
        this.title = title;
        this.dateStart = dateStart;
        this.dateEnd = dateEnd;
        this.payload = payload;
    }

    /**
     * scheduleId 반환. save 호출 전에는 null.
     */
    public Long getScheduleId() {
        return scheduleId;
    }

    /**
     * jobId UUID 반환(@Pattern 검증된 값).
     */
    public UUID getJobId() {
        return jobId;
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
     * 생성 시각 반환. @CreationTimestamp 로 채워진 값(save 직후에도 non-null).
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
