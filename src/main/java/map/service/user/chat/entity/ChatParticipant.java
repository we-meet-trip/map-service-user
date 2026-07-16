package map.service.user.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * ChatParticipant — 채팅방 참가자 JPA 엔티티
 *
 * user_service 스키마의 chat_participants 테이블에 매핑된다. 실제 인증 user_id
 * 참가자만 존재하며(익명 게스트 없음), (room_id, user_id) 는 UNIQUE 이다.
 * status 로 소프트 제거(나가기/강퇴)하여 메시지 기록과 무관하게 참가 이력을 남긴다.
 *
 * lastReadMessageSeq 는 카톡식 "안 읽은 인원수" 파생을 위한 읽음 포인터로,
 * 실제 갱신은 서비스에서 GREATEST(단조) UPDATE 로 수행한다.
 */
@Entity
@Table(
        name = "chat_participants",
        schema = "user_service",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_chat_participant",
                columnNames = {"room_id", "user_id"}
        )
)
public class ChatParticipant {

    /** 참가자 역할. OWNER 는 개설·초대·강퇴 권한을 가진다. */
    public enum Role {
        OWNER,
        MEMBER
    }

    /** 참가 상태. ACTIVE 만 전송·구독 가능. LEFT/KICKED 는 기록만 유지. */
    public enum Status {
        ACTIVE,
        LEFT,
        KICKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 8, nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 8, nullable = false)
    private Status status;

    @Column(name = "last_read_message_seq", nullable = false)
    private long lastReadMessageSeq;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    /** JPA 요구사항을 위한 보호 수준 기본 생성자. */
    protected ChatParticipant() {
    }

    /**
     * 도메인 생성자. status 는 ACTIVE, lastReadMessageSeq 는 0 으로 시작한다.
     *
     * roomId: 소속 방 식별자.
     * userId: 참가자 사용자 식별자.
     * role:   참가자 역할(OWNER/MEMBER).
     */
    public ChatParticipant(Long roomId, Long userId, Role role) {
        this.roomId = roomId;
        this.userId = userId;
        this.role = role;
        this.status = Status.ACTIVE;
    }

    /**
     * 읽음 포인터를 단조 전진시킨다(in-memory 경로용). 주어진 seq 가 현재 값보다 클 때만 갱신.
     * 다중 인스턴스/동시성 경로에서는 서비스의 GREATEST UPDATE 가 authoritative.
     */
    public void advanceReadPointer(long seq) {
        if (seq > this.lastReadMessageSeq) {
            this.lastReadMessageSeq = seq;
        }
    }

    /** 참가자를 재활성(ACTIVE)한다. 링크 재입장 시 사용. */
    public void reactivate() {
        this.status = Status.ACTIVE;
    }

    /** 스스로 나가기: 상태를 LEFT 로 전환(기록 유지). */
    public void leave() {
        this.status = Status.LEFT;
    }

    /** 강퇴: 상태를 KICKED 로 전환(기록 유지). */
    public void kick() {
        this.status = Status.KICKED;
    }

    public boolean isActive() {
        return this.status == Status.ACTIVE;
    }

    public boolean isOwner() {
        return this.role == Role.OWNER;
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public long getLastReadMessageSeq() {
        return lastReadMessageSeq;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }
}
