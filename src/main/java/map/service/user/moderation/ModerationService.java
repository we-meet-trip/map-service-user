package map.service.user.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.global.exception.*;
import map.service.user.recommend.RecommendJobRepository;
import map.service.user.schedule.ScheduleRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationService {
    private final ModerationReportRepository reports;
    private final ModerationActionRepository actions;
    private final UserBlockRepository blocks;
    private final ChatRestrictionRepository restrictions;
    private final ChatMessageRepository messages;
    private final UserRepository users;
    private final ScheduleRepository schedules;
    private final RecommendJobRepository jobs;
    private final PayloadCipher cipher;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    public ModerationService(ModerationReportRepository reports, ModerationActionRepository actions,
            UserBlockRepository blocks, ChatRestrictionRepository restrictions, ChatMessageRepository messages,
            UserRepository users, ScheduleRepository schedules, RecommendJobRepository jobs,
            PayloadCipher cipher, ObjectMapper mapper, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.reports=reports; this.actions=actions; this.blocks=blocks; this.restrictions=restrictions;
        this.messages=messages; this.users=users; this.schedules=schedules; this.jobs=jobs;
        this.cipher=cipher; this.mapper=mapper; this.jdbc=jdbc; this.events=events;
    }

    @Transactional
    public Submission submit(Long user, ReportRequest request) {
        validate(request);
        lockUser(user); // quota and idempotency are serialized across all instances; no Redis fail-open.
        String fingerprint=fingerprint(request);
        var previous=reports.findByReporterIdAndClientRequestId(user, request.clientRequestId());
        if (previous.isPresent()) {
            if (!fingerprint.equals(previous.get().getRequestFingerprint())) throw conflict();
            return new Submission(Receipt.of(previous.get()), false);
        }
        OffsetDateTime now=OffsetDateTime.now();
        if (reports.countByReporterIdAndCreatedAtAfter(user, now.minusMinutes(1))>=3
                || reports.countByReporterIdAndCreatedAtAfter(user, now.minusDays(1))>=10)
            throw new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
        ModerationReport report=new ModerationReport(user, request, fingerprint);
        switch(request.contentType()) {
            case CHAT_MESSAGE -> {
                ChatMessage message=messages.findReportable(request.roomId(), user, request.messageSeq())
                        .orElseThrow(ModerationService::notFound);
                if (message.getType()!=ChatMessage.MessageType.TEXT || message.getSenderId()==null)
                    throw notFound();
                report.targetMessage(message.getId(), message.getSenderId());
            }
            case TRIP -> {
                if (request.scheduleId()!=null && schedules.findByScheduleIdAndUserId(request.scheduleId(), user).isEmpty())
                    throw notFound();
                if (request.recommendJobId()!=null && jobs.findById(request.recommendJobId())
                        .filter(job->user.equals(job.getOwnerUserId())).isEmpty()) throw notFound();
            }
            case VISION -> { /* Description-only: there is no durable, owned inference reference yet. */ }
        }
        if (request.description()!=null && !request.description().isBlank()) {
            // Never silently persist a report containing personal data in plaintext.
            if (!cipher.isEnabled()) throw new CustomException(ErrorCode.MODERATION_UNAVAILABLE);
            report.setDescription(cipher.encrypt(request.description().strip(), aad(report)));
        }
        reports.save(report);
        return new Submission(Receipt.of(report), true);
    }

    @Transactional(readOnly=true)
    public List<Receipt> ownReports(Long user) {
        requireUser(user);
        return reports.findByReporterIdOrderByCreatedAtDesc(user, PageRequest.of(0,100)).stream().map(Receipt::of).toList();
    }
    @Transactional(readOnly=true)
    public List<BlockReceipt> ownBlocks(Long user) {
        requireUser(user);
        List<UserBlock> own=blocks.findByBlockerIdOrderByCreatedAtDesc(user);
        Map<Long,String> nicknames=new HashMap<>();
        users.findAllById(own.stream().map(UserBlock::getBlockedUserId).toList())
                .forEach(target->nicknames.put(target.getId(),target.getNickname()));
        return own.stream().map(b->new BlockReceipt(b.getBlockedUserId(), b.getCreatedAt(),nicknames.get(b.getBlockedUserId()))).toList();
    }
    @Transactional
    public void block(Long user, Long target) {
        if (target==null || target<=0 || target.equals(user)) throw invalid();
        lockUser(user);
        if (blocks.existsByBlockerIdAndBlockedUserId(user,target)) return;
        if (!users.existsById(target) || jdbc.queryForObject("""
                SELECT count(*) FROM user_service.chat_membership_intervals a
                JOIN user_service.chat_membership_intervals b ON b.room_id=a.room_id
                WHERE a.user_id=? AND b.user_id=?
                  AND (a.end_seq IS NULL OR b.start_seq < a.end_seq)
                  AND (b.end_seq IS NULL OR a.start_seq < b.end_seq)
                """, Long.class, user, target)==0) throw notFound();
        if (blocks.findByBlockerIdOrderByCreatedAtDesc(user).size()>=1000)
            throw new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
        blocks.save(new UserBlock(user,target));
    }
    @Transactional
    public void unblock(Long user, Long target) {
        lockUser(user);
        if (target==null || target<=0 || target.equals(user)) throw invalid();
        blocks.deleteByBlockerIdAndBlockedUserId(user,target);
    }

    @Transactional(readOnly=true)
    public List<Receipt> queue(ModerationReport.Status status, int limit) {
        return reports.findByStatusOrderByCreatedAtAsc(status, PageRequest.of(0,Math.max(1,Math.min(100,limit))))
                .stream().map(Receipt::of).toList();
    }
    @Transactional(readOnly=true)
    public AdminDetail detail(UUID id) {
        ModerationReport report=reports.findById(id).orElseThrow(ModerationService::notFound);
        ChatMessage message=report.getMessageId()==null ? null : messages.findById(report.getMessageId()).orElse(null);
        return new AdminDetail(Receipt.of(report), cipher.decrypt(report.getDescription(),aad(report)),
                report.getRoomId(), report.getMessageSeq(), report.getScheduleId(), report.getRecommendJobId(),
                message==null ? null : message.getContent(), actions.findByReportIdOrderByCreatedAtAsc(id).stream()
                .map(a->new ActionReceipt(a.getActionId(), a.getAction(), a.getAdminActor(), a.getRestrictionHours(),a.getCreatedAt())).toList());
    }
    @Transactional
    public Receipt act(UUID id, String actor, ActionRequest request) {
        // Opaque control-plane account reference; never an email, name, or user-provided label.
        if (actor==null || !actor.matches("[A-Za-z0-9_-]{1,96}") || request==null
                || request.actionId()==null || request.action()==null) throw invalid();
        boolean restrict=request.action()==ModerationAction.Action.RESTRICT_CHAT;
        if (restrict ? request.restrictionHours()==null || request.restrictionHours()<1 || request.restrictionHours()>720
                : request.restrictionHours()!=null) throw invalid();
        // Serialize restriction changes across different reports for the same author.
        // User lock precedes report lock, matching account-withdrawal lock ordering.
        if (request.action()==ModerationAction.Action.RESTRICT_CHAT || request.action()==ModerationAction.Action.LIFT_CHAT_RESTRICTION) {
            Long author=reports.findById(id).orElseThrow(ModerationService::notFound).getReportedUserId();
            if (author==null || users.findByIdForUpdate(author).isEmpty()) throw notFound();
        }
        ModerationReport report=reports.findForUpdate(id).orElseThrow(ModerationService::notFound);
        var previous=actions.findById(request.actionId());
        if (previous.isPresent()) {
            ModerationAction a=previous.get();
            if (!a.getReportId().equals(id) || !a.getAdminActor().equals(actor) || a.getAction()!=request.action()
                    || !Objects.equals(a.getRestrictionHours(),request.restrictionHours())) throw conflict();
            return Receipt.of(report);
        }
        if (report.getStatus()==ModerationReport.Status.DISMISSED
                || (report.getStatus()==ModerationReport.Status.ACTIONED
                    && request.action()!=ModerationAction.Action.LIFT_CHAT_RESTRICTION)) throw conflict();
        switch(request.action()) {
            case REVIEW -> report.transition(ModerationReport.Status.IN_REVIEW, "REVIEW");
            case DISMISS -> report.transition(ModerationReport.Status.DISMISSED, "DISMISS");
            case RESOLVE -> report.transition(ModerationReport.Status.ACTIONED, "RESOLVE");
            case HIDE_CHAT_MESSAGE -> {
                if (report.getContentType()!=ModerationReport.ContentType.CHAT_MESSAGE || report.getMessageId()==null)
                    throw invalid();
                ChatMessage message=messages.findById(report.getMessageId()).orElseThrow(ModerationService::notFound);
                message.hideForModeration();
                report.transition(ModerationReport.Status.ACTIONED, "HIDE_CHAT_MESSAGE");
                events.publishEvent(new MessageRemoved(message.getRoomId(), message.getSeq()));
            }
            case LIFT_CHAT_RESTRICTION -> {
                if (report.getContentType()!=ModerationReport.ContentType.CHAT_MESSAGE || report.getReportedUserId()==null
                        || report.getStatus()!=ModerationReport.Status.ACTIONED || !"RESTRICT_CHAT".equals(report.getResolution()))
                    throw invalid();
                restrictions.deleteById(report.getReportedUserId());
                report.transition(ModerationReport.Status.ACTIONED, "LIFT_CHAT_RESTRICTION");
            }
            case RESTRICT_CHAT -> {
                if (report.getContentType()!=ModerationReport.ContentType.CHAT_MESSAGE || report.getReportedUserId()==null)
                    throw invalid();
                OffsetDateTime until=OffsetDateTime.now().plusHours(request.restrictionHours());
                var existing=restrictions.findById(report.getReportedUserId());
                if (existing.isPresent() && existing.get().getRestrictedUntil().isAfter(until))
                    until=existing.get().getRestrictedUntil();
                restrictions.save(new ChatRestriction(report.getReportedUserId(),until));
                report.transition(ModerationReport.Status.ACTIONED, "RESTRICT_CHAT");
            }
        }
        actions.save(new ModerationAction(request.actionId(), id, actor, request.action(),request.restrictionHours()));
        return Receipt.of(report);
    }
    static void validate(ReportRequest r) {
        if (r==null || r.clientRequestId()==null || r.contentType()==null || r.reason()==null) throw invalid();
        if ((r.roomId()!=null && r.roomId()<=0) || (r.messageSeq()!=null && r.messageSeq()<=0)
                || (r.scheduleId()!=null && r.scheduleId()<=0)) throw invalid();
        String description=r.description();
        if (description!=null && (description.length()>1000 || description.toLowerCase(Locale.ROOT).contains("data:image")
                || description.matches("(?s).*[A-Za-z0-9+/]{256,}={0,2}.*"))) throw invalid();
        boolean chat=r.roomId()!=null && r.messageSeq()!=null && r.scheduleId()==null && r.recommendJobId()==null;
        boolean trip=r.roomId()==null && r.messageSeq()==null && ((r.scheduleId()!=null) ^ (r.recommendJobId()!=null));
        boolean vision=r.roomId()==null && r.messageSeq()==null && r.scheduleId()==null && r.recommendJobId()==null
                && description!=null && !description.isBlank();
        if (!(switch(r.contentType()) { case CHAT_MESSAGE->chat; case TRIP->trip; case VISION->vision; })) throw invalid();
    }
    private void lockUser(Long user) {
        if (user==null || users.findByIdForUpdate(user).isEmpty()) throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }
    private void requireUser(Long user) {
        if (user==null || !users.existsById(user)) throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }
    private String fingerprint(ReportRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException("Cannot fingerprint moderation request"); }
    }
    private static String aad(ModerationReport r) { return PayloadCipher.aad("moderation_reports","description",r.getReportId().toString()); }
    static CustomException invalid() { return new CustomException(ErrorCode.MODERATION_INVALID); }
    static CustomException conflict() { return new CustomException(ErrorCode.MODERATION_CONFLICT); }
    static CustomException notFound() { return new CustomException(ErrorCode.MODERATION_NOT_FOUND); }
    public record Submission(Receipt receipt, boolean created) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Receipt(UUID reportId, ModerationReport.Status status, ModerationReport.ContentType contentType,
            ModerationReport.Reason reason, String resolution, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static Receipt of(ModerationReport r) { return new Receipt(r.getReportId(),r.getStatus(),r.getContentType(),r.getReason(),
                r.getResolution(),r.getCreatedAt(),r.getUpdatedAt()); }
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BlockReceipt(Long blockedUserId, OffsetDateTime createdAt, String nickname) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActionRequest(UUID actionId, ModerationAction.Action action, Integer restrictionHours) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActionReceipt(UUID actionId, ModerationAction.Action action, String adminActor, Integer restrictionHours,OffsetDateTime createdAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AdminDetail(Receipt report, String description, Long roomId, Long messageSeq, Long scheduleId,
            UUID recommendJobId, String currentMessage, List<ActionReceipt> actions) {}
    public record MessageRemoved(Long roomId, long seq) {}
}
