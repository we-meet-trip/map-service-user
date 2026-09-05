package map.service.user.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * RecommendEditEntity — recommend_edits 매핑
 *
 * 사용자가 초안을 고친 전후를 남긴다. 무엇을 뺐는지가 가장 값진 신호인데,
 * 예전에는 수정이 초안을 덮어써 사후에 알 방법이 없었다.
 *
 * seq 는 같은 잡 안에서의 순번이며 (job_id, seq) 에 유일 제약이 걸려 있다.
 * 동시에 두 수정이 들어오면 한쪽이 제약에 걸려 되돌아간다 — 그 편이 둘 중
 * 하나가 조용히 사라지는 것보다 낫다.
 *
 * 매핑은 V011 DDL 과 일치해야 한다(ddl-auto=validate 는 불일치 시 부팅 실패).
 */
@Entity
@Table(name = "recommend_edits", schema = "user_service")
public class RecommendEditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    /** 고친 사람. 토큰 없이 들어온 요청이면 비어 있다. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** 같은 요청이 재시도로 두 번 들어와도 한 번만 남기기 위한 키. */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "before_payload", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode beforePayload;

    @Column(name = "after_payload", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode afterPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 요구사항을 위한 보호 수준 기본 생성자. */
    protected RecommendEditEntity() {
    }

    /** 도메인 생성자. id 와 createdAt 은 DB 가 채운다. */
    public RecommendEditEntity(UUID jobId, Integer seq, Long actorUserId,
                               String idempotencyKey,
                               JsonNode beforePayload, JsonNode afterPayload) {
        this.jobId = jobId;
        this.seq = seq;
        this.actorUserId = actorUserId;
        this.idempotencyKey = idempotencyKey;
        this.beforePayload = beforePayload;
        this.afterPayload = afterPayload;
    }

    /** 순번 반환. */
    public Integer getSeq() {
        return seq;
    }

    /** 대상 잡 반환. */
    public UUID getJobId() {
        return jobId;
    }

    /** 고친 사람 반환. 모르면 null. */
    public Long getActorUserId() {
        return actorUserId;
    }

    /** 고치기 직전 상태 반환. */
    public JsonNode getBeforePayload() {
        return beforePayload;
    }

    /** 고친 뒤 상태 반환. */
    public JsonNode getAfterPayload() {
        return afterPayload;
    }
}
