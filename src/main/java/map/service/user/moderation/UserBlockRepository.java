package map.service.user.moderation;

import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {
    boolean existsByBlockerIdAndBlockedUserId(Long blocker, Long blocked);
    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(Long blocker);
    void deleteByBlockerIdAndBlockedUserId(Long blocker, Long blocked);
    @Query("select count(b) from UserBlock b where (b.blockerId=:a and b.blockedUserId=:b) or (b.blockerId=:b and b.blockedUserId=:a)")
    long countBetween(@Param("a") Long a, @Param("b") Long b);
}
