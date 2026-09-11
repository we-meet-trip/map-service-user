package map.service.user.moderation;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ModerationReportRepository extends JpaRepository<ModerationReport, UUID> {
    Optional<ModerationReport> findByReporterIdAndClientRequestId(Long user, UUID request);
    long countByReporterIdAndCreatedAtAfter(Long user, OffsetDateTime since);
    List<ModerationReport> findByReporterIdOrderByCreatedAtDesc(Long user, Pageable page);
    List<ModerationReport> findByStatusOrderByCreatedAtAsc(ModerationReport.Status status, Pageable page);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ModerationReport r where r.reportId=:id")
    Optional<ModerationReport> findForUpdate(@Param("id") UUID id);
}
