package map.service.user.moderation;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
@Table(name="chat_restrictions", schema="user_service")
public class ChatRestriction {
    @Id @Column(name="user_id") private Long userId;
    @Column(name="restricted_until", nullable=false) private OffsetDateTime restrictedUntil;
    @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;
    public ChatRestriction(Long user, OffsetDateTime until) { userId=user; restrictedUntil=until; updatedAt=OffsetDateTime.now(); }
}
