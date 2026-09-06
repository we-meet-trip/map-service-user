package map.service.user.chat.repository;

import java.util.List;
import java.util.Optional;
import map.service.user.chat.entity.ChatParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ChatParticipantRepository — 참가자 영속화 JPA 리포지토리
 *
 * 참가 조회, 상태별 목록/개수, 그리고 읽음 포인터의 단조 갱신을 제공한다.
 */
public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    /** (방, 사용자) 로 참가행 조회. UNIQUE 이므로 최대 1건. */
    Optional<ChatParticipant> findByRoomIdAndUserId(Long roomId, Long userId);

    /** 방의 특정 상태 참가자 목록. */
    List<ChatParticipant> findByRoomIdAndStatus(Long roomId, ChatParticipant.Status status);

    /** 방의 특정 상태 참가자 수. 상한(10명) 검사에 사용. */
    long countByRoomIdAndStatus(Long roomId, ChatParticipant.Status status);

    List<ChatParticipant> findByUserIdAndStatusNot(Long userId, ChatParticipant.Status status);

    /** 사용자가 특정 상태로 속한 방 목록(내 채팅방 목록). */
    List<ChatParticipant> findByUserIdAndStatus(Long userId, ChatParticipant.Status status);

    /**
     * 방의 특정 상태 참가자들의 읽음 포인터(last_read_message_seq) 목록.
     * 메시지별 안읽은 인원수를 O(log n) 이분탐색으로 파생하기 위해 한 번에 로드한다.
     */
    @Query("select p.lastReadMessageSeq from ChatParticipant p "
            + "where p.roomId = :roomId and p.status = :status")
    List<Long> findReadPointers(@Param("roomId") Long roomId,
                                @Param("status") ChatParticipant.Status status);

    /**
     * 읽음 포인터를 단조(GREATEST) 갱신한다. 주어진 seq 가 현재 값보다 클 때만 전진하므로
     * 순서가 뒤바뀐/동시 읽음이 포인터를 되돌리지 않는다. 참가자별 다른 행이라 락 경합 없음.
     * 갱신된 행 수를 반환한다(참가행 부재/역행 시 0).
     */
    @Modifying
    @Query("update ChatParticipant p set p.lastReadMessageSeq = :seq "
            + "where p.roomId = :roomId and p.userId = :userId and p.lastReadMessageSeq < :seq")
    int advanceReadPointer(@Param("roomId") Long roomId,
                           @Param("userId") Long userId,
                           @Param("seq") long seq);
}
