package map.service.user.chat.entity;

import jakarta.persistence.*;

/** An authorized interval is (startSeq, endSeq]; null end denotes current participation. */
@Entity
@Table(name = "chat_membership_intervals", schema = "user_service")
public class ChatMembershipInterval {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="room_id", nullable=false) private Long roomId;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="start_seq", nullable=false) private long startSeq;
    @Column(name="end_seq") private Long endSeq;
    protected ChatMembershipInterval() {}
    public ChatMembershipInterval(Long roomId, Long userId, long startSeq) {
        this.roomId = roomId; this.userId = userId; this.startSeq = startSeq;
    }
}
