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
 * RecommendTrainingEntity — recommend_training 매핑
 *
 * 추천 한 건이 "무엇을 보고 무엇을 골랐는지" 를 담는다. agent 가 완료 이벤트에
 * 함께 실어 보낸 것을 그대로 보관한다.
 *
 * recommend_jobs 와 나눠 둔 이유: 잡 상태는 목록·통계에서 자주 읽는데, 후보
 * 수십 개(건당 십수 KB)를 같은 행에 두면 그 조회가 매번 함께 무거워진다.
 *
 * payload 를 통째로 JSONB 로 두고 컬럼으로 펼치지 않은 이유: 담는 내용이 아직
 * 굳지 않았다. 컬럼으로 펼치면 신호를 하나 더할 때마다 마이그레이션이 필요하고,
 * 그러면 실제로는 더하지 않게 된다. 대신 schemaVersion 으로 판을 구분한다.
 *
 * 매핑은 V011 DDL 과 일치해야 한다(ddl-auto=validate 는 불일치 시 부팅 실패).
 */
@Entity
@Table(name = "recommend_training", schema = "user_service")
public class RecommendTrainingEntity {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 요구사항을 위한 보호 수준 기본 생성자. */
    protected RecommendTrainingEntity() {
    }

    /** 도메인 생성자. createdAt 은 DB 기본값으로 채워지므로 인자에 없다. */
    public RecommendTrainingEntity(UUID jobId, Integer schemaVersion, JsonNode payload) {
        this.jobId = jobId;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
    }

    /** 대상 잡 식별자 반환. */
    public UUID getJobId() {
        return jobId;
    }

    /** 신호 계약의 판 반환. */
    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    /** 신호 본문 반환. */
    public JsonNode getPayload() {
        return payload;
    }

    /** 기록 시각 반환. */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
