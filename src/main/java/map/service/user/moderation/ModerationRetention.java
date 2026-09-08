package map.service.user.moderation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only newly introduced moderation tables. Existing chat/control audit rows are untouched. */
@Service
public class ModerationRetention {
    private final JdbcTemplate jdbc;
    public ModerationRetention(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${moderation.retention.delay-ms:3600000}",
            initialDelayString="${moderation.retention.initial-delay-ms:300000}")
    @Transactional
    public void sweep() { prune(OffsetDateTime.now(ZoneOffset.UTC)); }

    Counts prune(OffsetDateTime now) {
        int scrubbed=jdbc.update("""
                UPDATE user_service.moderation_reports SET description=NULL, request_fingerprint=NULL,
                    message_id=NULL, room_id=NULL, message_seq=NULL, schedule_id=NULL, recommend_job_id=NULL,
                    reported_user_id=NULL
                WHERE report_id IN (SELECT report_id FROM user_service.moderation_reports
                    WHERE status IN ('ACTIONED','DISMISSED') AND updated_at<=?
                    AND (description IS NOT NULL OR request_fingerprint IS NOT NULL OR message_id IS NOT NULL
                        OR room_id IS NOT NULL OR message_seq IS NOT NULL OR schedule_id IS NOT NULL
                        OR recommend_job_id IS NOT NULL OR reported_user_id IS NOT NULL)
                    ORDER BY updated_at LIMIT 500)
                """, now.minusDays(90));
        int actions=jdbc.update("""
                DELETE FROM user_service.moderation_actions WHERE action_id IN (
                    SELECT action_id FROM user_service.moderation_actions WHERE created_at<=?
                    ORDER BY created_at LIMIT 500)
                """, now.minusDays(365));
        int reports=jdbc.update("""
                DELETE FROM user_service.moderation_reports WHERE report_id IN (
                    SELECT r.report_id FROM user_service.moderation_reports r
                    WHERE r.status IN ('ACTIONED','DISMISSED') AND r.updated_at<=?
                    AND NOT EXISTS (SELECT 1 FROM user_service.moderation_actions a WHERE a.report_id=r.report_id)
                    ORDER BY r.updated_at LIMIT 500)
                """, now.minusDays(365));
        return new Counts(scrubbed,actions,reports);
    }
    record Counts(int scrubbed, int actionsDeleted, int reportsDeleted) {}
}
