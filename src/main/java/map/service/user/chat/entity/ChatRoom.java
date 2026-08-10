package map.service.user.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * ChatRoom — 채팅방 JPA 엔티티
 *
 * user_service 스키마의 chat_rooms 테이블에 매핑된다. 저장된 일정(schedules)에
 * schedule_id 로 1:1 앵커되며, 방의 만료(expires_at)·초대 링크 상태·룸별 메시지
 * seq 카운터를 보유한다.
 *
 * PK(roomId)는 DB 자동 증가로 채워지고, 다른 테이블을 가리키는 식별자는 관계 매핑
 * 없이 Long 컬럼으로만 보관하며, createdAt 은 영속 시점에 자동 기록된다.
 *
 * 필드:
 * - roomId             : PK. 컬럼명 room_id.
 * - scheduleId         : 앵커 일정 식별자. UNIQUE. NOT NULL.
 * - ownerId            : 소유자(= schedules.user_id 스냅샷). NOT NULL.
 * - title              : 일정 제목 스냅샷.
 * - nextSeq            : 룸별 단조 메시지 카운터. allocateNextSeq 로 +1 하여 발급.
 * - inviteTokenHash    : 초대 원시토큰의 SHA-256 hex. NULL = 활성 링크 없음.
 * - inviteTokenVersion : 재발급/폐기마다 증가. 구 링크 무효화용.
 * - inviteRevoked      : 링크 폐기 여부.
 * - inviteExpiresAt    : 링크 자체의 만료 시각. NULL = 독립 만료 없음(구 링크).
 * - expiresAt          : date_end + 7일 23:59:59 Asia/Seoul 스냅샷. NOT NULL.
 * - readOnly           : sweep 가 만료 시 전환. 전송 차단의 authoritative 는 실검사(isExpired).
 * - createdAt          : 생성 시각. @CreationTimestamp(updatable=false).
 */
@Entity
@Table(
        name = "chat_rooms",
        schema = "user_service",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_chat_rooms_schedule",
                columnNames = "schedule_id"
        )
)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "title")
    private String title;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    @Column(name = "invite_token_hash", length = 64)
    private String inviteTokenHash;

    @Column(name = "invite_token_version", nullable = false)
    private int inviteTokenVersion;

    @Column(name = "invite_revoked", nullable = false)
    private boolean inviteRevoked;

    @Column(name = "invite_expires_at")
    private OffsetDateTime inviteExpiresAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "read_only", nullable = false)
    private boolean readOnly;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 요구사항을 위한 보호 수준 기본 생성자. */
    protected ChatRoom() {
    }

    /**
     * 도메인 생성자.
     *
     * roomId/createdAt 은 영속 시 채워지므로 인자에 없다. nextSeq=0, inviteTokenVersion=0,
     * inviteRevoked=false, readOnly=false 는 원시 기본값이며 DB DEFAULT 와 정합.
     *
     * scheduleId: 앵커 일정 식별자.
     * ownerId:    소유자 식별자.
     * title:      일정 제목 스냅샷.
     * expiresAt:  만료 시각 스냅샷.
     */
    public ChatRoom(Long scheduleId, Long ownerId, String title, OffsetDateTime expiresAt) {
        this.scheduleId = scheduleId;
        this.ownerId = ownerId;
        this.title = title;
        this.expiresAt = expiresAt;
    }

    /**
     * 다음 메시지 seq 를 발급한다. nextSeq 를 1 증가시키고 그 값을 반환한다.
     * 첫 메시지는 seq=1 을 받으며, last_read_message_seq 기본값 0 보다 크므로
     * 초기 상태에서 미읽음으로 집계된다.
     *
     * 호출자는 반드시 chat_rooms 행을 PESSIMISTIC_WRITE 로 잠근 상태에서 호출해야
     * 룸 내 seq 연속성이 보장된다(ChatRoomRepository.findByIdForUpdate).
     */
    public long allocateNextSeq() {
        this.nextSeq += 1;
        return this.nextSeq;
    }

    /**
     * 주어진 시각 기준 방이 만료되었는지 반환한다. now >= expiresAt 이면 만료.
     * sweep 실행 전에도 정확하도록 실시간 시각으로 판정한다.
     */
    public boolean isExpired(OffsetDateTime now) {
        return !now.isBefore(this.expiresAt);
    }

    /**
     * 초대 링크를 (재)발급한다. 새 토큰 해시를 설정하고 버전을 증가시키며 폐기 상태를 해제한다.
     * 버전 증가로 이전에 배포된 링크가 무효화된다.
     *
     * 만료 시각을 인자로 강제하는 이유: 인자 없는 형태를 함께 두면 나중에 그쪽을 부른
     * 코드가 만료 없는 링크를 조용히 발급하게 된다. 발급하는 쪽이 언제까지 살릴지를
     * 매번 밝히도록 한다.
     */
    public void rotateInvite(String newTokenHash, OffsetDateTime inviteExpiresAt) {
        this.inviteTokenHash = newTokenHash;
        this.inviteTokenVersion += 1;
        this.inviteRevoked = false;
        this.inviteExpiresAt = inviteExpiresAt;
    }

    /**
     * 초대 링크를 폐기한다. 토큰 해시를 제거하고 버전을 증가시키며 폐기 플래그를 세운다.
     * 이후 구 링크의 토큰 해시는 매칭되지 않는다.
     *
     * 만료 시각도 함께 비운다. 가리킬 링크가 없는데 만료만 남겨 두면 그 행을 읽는
     * 쪽이 아직 살아 있는 링크가 있다고 오해한다.
     */
    public void revokeInvite() {
        this.inviteTokenHash = null;
        this.inviteTokenVersion += 1;
        this.inviteRevoked = true;
        this.inviteExpiresAt = null;
    }

    /**
     * 주어진 시각 기준 초대 링크가 스스로 만료되었는지 반환한다.
     * 독립 만료가 없는 구 링크(NULL)는 여기서 만료로 보지 않는다 — 그 경우 방 만료가 판정한다.
     */
    public boolean isInviteExpired(OffsetDateTime now) {
        return this.inviteExpiresAt != null && !now.isBefore(this.inviteExpiresAt);
    }

    /**
     * 링크가 실제로 언제까지 쓸 수 있는지. 링크 만료와 방 만료 중 이른 쪽이다.
     *
     * 방이 먼저 닫히면 링크가 살아 있어도 들어갈 곳이 없으므로, 둘 중 이른 시각이
     * 사용자가 체감하는 유효기간이 된다. 이 계산을 여기 한 곳에만 두어 응답마다
     * 다른 값이 나가는 일을 막는다.
     */
    public OffsetDateTime effectiveInviteExpiresAt() {
        if (this.inviteExpiresAt == null) {
            return this.expiresAt;
        }
        return this.inviteExpiresAt.isBefore(this.expiresAt) ? this.inviteExpiresAt : this.expiresAt;
    }

    /**
     * 방을 읽기전용(보관)으로 전환한다. 만료 sweep 및 소유자 나가기 시 사용한다.
     */
    public void close() {
        this.readOnly = true;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public long getNextSeq() {
        return nextSeq;
    }

    public String getInviteTokenHash() {
        return inviteTokenHash;
    }

    public int getInviteTokenVersion() {
        return inviteTokenVersion;
    }

    public boolean isInviteRevoked() {
        return inviteRevoked;
    }

    public OffsetDateTime getInviteExpiresAt() {
        return inviteExpiresAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
