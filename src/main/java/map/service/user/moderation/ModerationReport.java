package map.service.user.moderation;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
@Table(name="moderation_reports", schema="user_service", uniqueConstraints=@UniqueConstraint(columnNames={"reporter_id","client_request_id"}))
public class ModerationReport {
    public enum ContentType { CHAT_MESSAGE, TRIP, VISION, REVIEW_SUMMARY }
    public enum Reason { HARASSMENT, HATE, SEXUAL_CONTENT, VIOLENCE, DANGEROUS_OR_ILLEGAL, SPAM, PRIVACY, INACCURATE, OTHER }
    public enum Status { OPEN, IN_REVIEW, ACTIONED, DISMISSED }
    @Id @Column(name="report_id") private UUID reportId;
    @Column(name="reporter_id") private Long reporterId;
    @Column(name="client_request_id", nullable=false) private UUID clientRequestId;
    @Enumerated(EnumType.STRING) @Column(name="content_type", nullable=false, length=24) private ContentType contentType;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=32) private Reason reason;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=16) private Status status;
    @Column(columnDefinition="text") private String description;
    @Column(name="request_fingerprint", length=64) private String requestFingerprint;
    @Column(name="message_id") private Long messageId;
    @Column(name="room_id") private Long roomId;
    @Column(name="message_seq") private Long messageSeq;
    @Column(name="schedule_id") private Long scheduleId;
    @Column(name="recommend_job_id") private UUID recommendJobId;
    @Column(name="reported_user_id") private Long reportedUserId;
    @Column(length=24) private String resolution;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;

    public ModerationReport(Long reporter, ReportRequest request, String fingerprint) {
        reportId=UUID.randomUUID(); reporterId=reporter; clientRequestId=request.clientRequestId();
        contentType=request.contentType(); reason=request.reason(); status=Status.OPEN;
        requestFingerprint=fingerprint; roomId=request.roomId(); messageSeq=request.messageSeq();
        scheduleId=request.scheduleId(); recommendJobId=request.recommendJobId();
        createdAt=OffsetDateTime.now(); updatedAt=createdAt;
    }
    public void setDescription(String sealed) { description=sealed; }
    public void targetMessage(Long message, Long author) { messageId=message; reportedUserId=author; }
    public void transition(Status next, String action) { status=next; resolution=action; updatedAt=OffsetDateTime.now(); }
}
