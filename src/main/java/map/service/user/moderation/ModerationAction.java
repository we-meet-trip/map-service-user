package map.service.user.moderation;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
@Table(name="moderation_actions", schema="user_service")
public class ModerationAction {
    public enum Action { REVIEW, DISMISS, HIDE_CHAT_MESSAGE, RESTRICT_CHAT, LIFT_CHAT_RESTRICTION, RESOLVE }
    @Id @Column(name="action_id") private UUID actionId;
    @Column(name="report_id", nullable=false) private UUID reportId;
    @Column(name="admin_actor", nullable=false, length=96) private String adminActor;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) private Action action;
    @Column(name="restriction_hours") private Integer restrictionHours;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    public ModerationAction(UUID id, UUID report, String actor, Action action, Integer hours) {
        actionId=id; reportId=report; adminActor=actor; this.action=action;
        restrictionHours=hours; createdAt=OffsetDateTime.now();
    }
}
