package map.service.user.chat.repository;

import map.service.user.chat.entity.ChatMembershipInterval;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ChatMembershipIntervalRepository extends JpaRepository<ChatMembershipInterval, Long> {
    @Modifying
    @Query("update ChatMembershipInterval i set i.endSeq = :seq where i.roomId = :room and i.userId = :uid and i.endSeq is null")
    void close(@Param("room") Long roomId, @Param("uid") Long userId, @Param("seq") long seq);
}
