package map.service.user.chat.repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import map.service.user.chat.entity.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ChatRoomRepository — 채팅방 영속화 JPA 리포지토리
 *
 * 기본 CRUD 는 JpaRepository 에서 상속하며, 방 조회와 seq/상한 직렬화를 위한
 * 비관적 락 조회를 추가로 제공한다.
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /** 앵커 일정으로 방 조회(schedule ↔ room 1:1). */
    Optional<ChatRoom> findByScheduleId(Long scheduleId);

    /** 초대 토큰 해시로 방 조회(초대 링크 해석). */
    Optional<ChatRoom> findByInviteTokenHash(String inviteTokenHash);

    /**
     * 방 행을 PESSIMISTIC_WRITE 로 잠근 뒤 조회한다.
     *
     * 메시지 seq 발급(allocateNextSeq)과 참가자 상한(10명) 검사를 동일 락 하에서
     * 직렬화하기 위해 사용한다. 반드시 @Transactional 컨텍스트에서 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.roomId = :roomId")
    Optional<ChatRoom> findByIdForUpdate(@Param("roomId") Long roomId);

    /**
     * 만료 시각을 지났으나 아직 보관 전용으로 전환되지 않은 방을 조회한다.
     * 만료 sweep 이 이 목록을 읽어 read_only 로 전환하고 종료를 알린다.
     */
    @Query("select r from ChatRoom r where r.readOnly = false and r.expiresAt <= :now")
    List<ChatRoom> findExpiredOpenRooms(@Param("now") OffsetDateTime now);
}
