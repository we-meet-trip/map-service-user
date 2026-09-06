package map.service.user.moderation;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Getter @NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
@Table(name="user_blocks", schema="user_service", uniqueConstraints=@UniqueConstraint(columnNames={"blocker_id","blocked_user_id"}))
public class UserBlock {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="blocker_id", nullable=false) private Long blockerId;
    @Column(name="blocked_user_id", nullable=false) private Long blockedUserId;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    public UserBlock(Long blocker, Long blocked) {
        blockerId=blocker; blockedUserId=blocked; createdAt=OffsetDateTime.now();
    }
}
