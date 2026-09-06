package map.service.user.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Short-lived operational retry receipt; never a training before/after snapshot. */
@Entity
@Table(name = "recommend_edit_requests", schema = "user_service")
public class RecommendEditRequest {
    @Id @Column(length = 64)
    private String id;
    @Column(name = "job_id", nullable = false)
    private UUID jobId;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode responsePayload;
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected RecommendEditRequest() {}

    public RecommendEditRequest(String id, UUID jobId, String requestHash,
                                JsonNode responsePayload, OffsetDateTime expiresAt) {
        this.id = id;
        this.jobId = jobId;
        this.requestHash = requestHash;
        this.responsePayload = responsePayload;
        this.expiresAt = expiresAt;
    }
    public String getRequestHash() { return requestHash; }
    public JsonNode getResponsePayload() { return responsePayload; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
