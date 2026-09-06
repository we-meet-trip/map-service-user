package map.service.user.chat.repository;

import java.util.List;
import java.util.Optional;
import map.service.user.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ChatMessageRepository — 메시지 영속화 JPA 리포지토리
 *
 * seq 내림차순 커서 페이징(idx_chat_messages_room_seq 활용)과 최신 메시지 조회를
 * 제공한다. 첫 페이지는 커서 없이, 이후 페이지는 before_seq 커서로 조회한다.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @org.springframework.data.jpa.repository.Query("select m from ChatMessage m where m.roomId = :room "
            + "and m.seq < :before and exists (select i.id from ChatMembershipInterval i "
            + "where i.roomId = m.roomId and i.userId = :uid and m.seq > i.startSeq "
            + "and (i.endSeq is null or m.seq <= i.endSeq)) order by m.seq desc")
    List<ChatMessage> findVisible(@org.springframework.data.repository.query.Param("room") Long roomId,
                                 @org.springframework.data.repository.query.Param("uid") Long userId,
                                 @org.springframework.data.repository.query.Param("before") long before,
                                 Pageable page);

    @org.springframework.data.jpa.repository.Query("select count(m) from ChatMessage m where m.roomId = :room "
            + "and m.seq > :after and exists (select i.id from ChatMembershipInterval i "
            + "where i.roomId = m.roomId and i.userId = :uid and m.seq > i.startSeq "
            + "and (i.endSeq is null or m.seq <= i.endSeq))")
    long countVisibleAfter(@org.springframework.data.repository.query.Param("room") Long roomId,
                            @org.springframework.data.repository.query.Param("uid") Long userId,
                            @org.springframework.data.repository.query.Param("after") long after);

    /** 첫 페이지: 방의 최신 메시지부터 seq 내림차순. */
    List<ChatMessage> findByRoomIdOrderBySeqDesc(Long roomId, Pageable pageable);

    /** 커서 페이지: 주어진 seq 미만의 메시지를 seq 내림차순으로. */
    List<ChatMessage> findByRoomIdAndSeqLessThanOrderBySeqDesc(Long roomId, long seq, Pageable pageable);

    /** 방의 최신 메시지 1건(최신 seq/미리보기용). */
    Optional<ChatMessage> findTopByRoomIdOrderBySeqDesc(Long roomId);
}
