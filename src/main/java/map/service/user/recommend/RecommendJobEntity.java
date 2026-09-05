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
     * 요청에 실려 온 광역시도/시군구. 추천 결과 payload 에는 지역이 남지 않아
     * 여기서만 보존된다. 이 작업으로 저장되는 일정이 날씨를 다시 물으려면
     * 지역이 필요하다(hub 날씨는 좌표가 아니라 지역명으로 묻는다).
     */
    @Column(name = "province", length = 20)
    private String province;

    @Column(name = "city", length = 20)
    private String city;
     /**
     * 이 잡을 만든 경로. init | research | route | refresh.
     * 이 마이그레이션 이전 행은 비어 있으며 "미상" 으로 읽는다.
     */
    @Column(name = "mode", length = 16)
    private String mode;

    /** 재탐색일 때 거부된 원본 잡. 논리 참조이며 외래키를 걸지 않는다. */
    @Column(name = "parent_job_id")
    private UUID parentJobId;

    /**
     * 결과를 실제로 만든 주체. agent | cache_hit.
     * 캐시로 답한 잡은 agent 가 돌지 않았는데도 완료로 기록되므로, 세는 쪽이
     * 이 값으로 갈라 보지 않으면 LLM 사용량이 부풀어 보인다.
     */
    @Column(name = "source", length = 16)
    private String source;

    /** 함께 보관된 학습 신호의 계약 판. 신호가 없으면 비어 있다. */
    @Column(name = "schema_version")
    private Integer schemaVersion;

    /**
     * 이 잡을 만든 사용자. 토큰 없이 들어온 요청과 이전 행은 비어 있다.
     * 비어 있으면 소유자를 모르는 것이므로 수정을 막지 않는다.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /**
     * 잡을 만든 시점의 요청자 성향(연령대·성별·테마·취향병합여부).
     *
     * 스냅샷인 이유: 사람이 나중에 프로필을 고치면 조인으로 읽는 값은 함께
     * 바뀌어, 같은 기록을 두 번 읽을 때 다른 입력이 나온다. 물어본 그때의
     * 값을 붙여 두어야 학습과 재현이 어긋나지 않는다.
     *
     * 원문(생년월일 등)은 담지 않는다 — 학습에 필요한 만큼만 옮긴다.
     */
    @Column(name = "user_segment", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode userSegment;

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

    /** 요청자 성향 스냅샷 반환. nullable(모르면 비어 있다). */
    public JsonNode getUserSegment() {
        return userSegment;
    }

    /**
     * 성향 스냅샷을 처음 한 번만 새긴다.
     *
     * 이미 있으면 두지 않는다. 같은 잡에 접수 기록이 두 번 들어와도 처음
     * 물어본 시점의 값이 남아야 하기 때문이다 — 나중 값으로 덮으면 스냅샷을
     * 두는 뜻이 사라진다.
     */
    public void fillUserSegmentIfAbsent(JsonNode segment) {
        if (userSegment == null && segment != null) {
            userSegment = segment;
        }
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

    /** 요청 광역시도 반환. 지역 없이 만들어진 작업이면 null. */
    public String getProvince() {
        return province;
    }

    /** 요청 시군구 반환. 지역 없이 만들어진 작업이면 null. */
    public String getCity() {
        return city;
    }

    /**
     * 요청 지역을 새긴다. 둘 중 하나라도 비면 둘 다 비운다 — 반쪽 지역으로는
     * hub 에 날씨를 물을 수 없다.
     */
    public void setRegion(String province, String city) {
        boolean usable = province != null && !province.isBlank()
                && city != null && !city.isBlank();
        this.province = usable ? province : null;
        this.city = usable ? city : null;
    }

    /** 이 잡을 만든 경로 반환. 이전 행은 null(미상). */
    public String getMode() {
        return mode;
    }

    /** 재탐색일 때 거부된 원본 잡 반환. nullable. */
    public UUID getParentJobId() {
        return parentJobId;
    }

    /** 결과를 만든 주체 반환(agent/cache_hit). 이전 행은 null(미상). */
    public String getSource() {
        return source;
    }

    /** 함께 보관된 학습 신호의 계약 판 반환. nullable. */
    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    /** 이 잡을 만든 사용자 반환. 모르면 null. */
    public Long getOwnerUserId() {
        return ownerUserId;
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
