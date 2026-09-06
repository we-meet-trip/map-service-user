package map.service.user.chat.entity;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * ChatMessage — 채팅 메시지 JPA 엔티티
 *
 * user_service 스키마의 chat_messages 테이블에 매핑된다. 텍스트 메시지와 시스템
 * 메시지(입장/강퇴/방 종료, "일정 보러가기" 카드 등)를 모두 담는다.
 *
 * seq 는 룸별 단조 값으로 방마다 1 부터 증가하며 (room_id, seq) 는 UNIQUE 이다.
 * system_payload 는 카드 등 구조화 데이터를 담는 nullable JSONB 컬럼으로, 문자열이
 * 아닌 JSON 노드 그대로 저장·복원된다.
 */
@Entity
@Table(
        name = "chat_messages",
        schema = "user_service",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_chat_message_seq",
                columnNames = {"room_id", "seq"}
        )
)
public class ChatMessage {

    /** 메시지 종류. TEXT 는 사용자 텍스트, SYSTEM 은 안내/카드 메시지(sender 없음). */
    public enum MessageType {
        TEXT,
        SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "sender_id")
    private Long senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 8, nullable = false)
    private MessageType type;

    @Column(name = "content")
    private String content;

    @Column(name = "moderation_hidden", nullable = false)
    private boolean moderationHidden;

    public boolean isModerationHidden() { return moderationHidden; }
    public void hideForModeration() { moderationHidden=true; }

    @Column(name = "system_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode systemPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** JPA 요구사항을 위한 보호 수준 기본 생성자. */
    protected ChatMessage() {
    }

    private ChatMessage(Long roomId, long seq, Long senderId, MessageType type,
                        String content, JsonNode systemPayload) {
        this.roomId = roomId;
        this.seq = seq;
        this.senderId = senderId;
        this.type = type;
        this.content = content;
        this.systemPayload = systemPayload;
    }

    /**
     * 텍스트 메시지 팩토리. senderId 는 발신자(NOT NULL 의미), systemPayload 없음.
     */
    public static ChatMessage text(Long roomId, long seq, Long senderId, String content) {
        return new ChatMessage(roomId, seq, senderId, MessageType.TEXT, content, null);
    }

    /**
     * 시스템 메시지 팩토리. senderId 는 null, content 는 안내 문구, systemPayload 는
     * 카드 payload(예: 일정 보러가기) 로 nullable.
     */
    public static ChatMessage system(Long roomId, long seq, String content, JsonNode systemPayload) {
        return new ChatMessage(roomId, seq, null, MessageType.SYSTEM, content, systemPayload);
    }

    public Long getId() {
        return id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public long getSeq() {
        return seq;
    }

    public Long getSenderId() {
        return senderId;
    }

    public MessageType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public JsonNode getSystemPayload() {
        return systemPayload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
