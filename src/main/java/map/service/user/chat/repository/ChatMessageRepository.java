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

    /** 첫 페이지: 방의 최신 메시지부터 seq 내림차순. */
    List<ChatMessage> findByRoomIdOrderBySeqDesc(Long roomId, Pageable pageable);

    /** 커서 페이지: 주어진 seq 미만의 메시지를 seq 내림차순으로. */
    List<ChatMessage> findByRoomIdAndSeqLessThanOrderBySeqDesc(Long roomId, long seq, Pageable pageable);

    /** 방의 최신 메시지 1건(최신 seq/미리보기용). */
    Optional<ChatMessage> findTopByRoomIdOrderBySeqDesc(Long roomId);
}
