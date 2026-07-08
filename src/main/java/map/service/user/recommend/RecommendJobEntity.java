package map.service.user.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * RecommendJobEntity — 추천 작업 상태/결과 JPA 엔티티
 *
 * user_service 스키마의 recommend_jobs 테이블에 매핑된다(V005). 추천 작업의
 * 상태와 완료 결과를 PostgreSQL 에 write-through 로 보존하여, Redis draft 가
 * 만료/유실된 뒤에도 완료 결과를 폴백 조회할 수 있게 한다.
 *
 * 매핑은 V005 DDL 과 정확히 일치해야 한다(ddl-auto=validate 는 불일치 시 부팅 실패).
 *
 * 필드:
 * - jobId: PK. agent 발급 작업 UUID. 컬럼명 job_id(@Id, 애플리케이션 할당).
 * - scheduleId: 연관 일정 식별자. 컬럼명 schedule_id(최대 64자, nullable).
 * - status: 작업 상태(in_progress/done/failed). NOT NULL. 컬럼명 status.
 * - resultPayload: 완료 결과 JSON 스냅샷. JSONB + @JdbcTypeCode(SqlTypes.JSON). nullable.
 * - error: 실패 사유 텍스트. nullable.
 * - createdAt: 생성 시각. @CreationTimestamp(updatable=false). DB DEFAULT now() 와 정합.
 * - finishedAt: 완료 기록 시각. nullable.
 */
@Entity
@Table(name = "recommend_jobs", schema = "user_service")
public class RecommendJobEntity {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "schedule_id", length = 64)
    private String scheduleId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "result_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode resultPayload;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /**
     * JPA 요구사항을 위한 보호 수준 기본 생성자.
     */
    protected RecommendJobEntity() {
    }

    /**
     * 도메인 생성자.
     *
     * createdAt 은 @CreationTimestamp / DB 기본값으로 채워지므로 인자에 없다.
     *
     * jobId: 작업 UUID(PK, 애플리케이션 할당).
     * scheduleId: 연관 일정 식별자(nullable).
     * status: 작업 상태(in_progress/done/failed).
     * resultPayload: 결과 JSON 스냅샷(nullable).
     * error: 실패 사유(nullable).
     * finishedAt: 완료 기록 시각(nullable).
     */
    public RecommendJobEntity(
            UUID jobId,
            String scheduleId,
            String status,
            JsonNode resultPayload,
            String error,
            OffsetDateTime finishedAt
    ) {
        this.jobId = jobId;
        this.scheduleId = scheduleId;
        this.status = status;
        this.resultPayload = resultPayload;
        this.error = error;
        this.finishedAt = finishedAt;
    }

    /** 작업 UUID 반환. */
    public UUID getJobId() {
        return jobId;
    }

    /** 연관 일정 식별자 반환. nullable. */
    public String getScheduleId() {
        return scheduleId;
    }

    /** 작업 상태 반환(in_progress/done/failed). */
    public String getStatus() {
        return status;
    }

    /** 결과 JSON 스냅샷 반환. nullable. */
    public JsonNode getResultPayload() {
        return resultPayload;
    }

    /** 실패 사유 반환. nullable. */
    public String getError() {
        return error;
    }

    /** 생성 시각 반환. */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** 완료 기록 시각 반환. nullable. */
    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    /** 상태를 갱신한다(완료 기록 시 사용). */
    public void setStatus(String status) {
        this.status = status;
    }

    /** 결과 JSON 스냅샷을 갱신한다. */
    public void setResultPayload(JsonNode resultPayload) {
        this.resultPayload = resultPayload;
    }

    /** 완료 기록 시각을 갱신한다. */
    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
