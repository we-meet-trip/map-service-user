package map.service.user.moderation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.entity.*;
import map.service.user.chat.repository.*;
import map.service.user.chat.service.*;
import map.service.user.chat.ws.*;
import map.service.user.domain.user.entity.*;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.config.*;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.global.exception.*;
import map.service.user.recommend.RecommendJobRepository;
import map.service.user.schedule.ScheduleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.*;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ModerationIntegrationTest {
    @Autowired ModerationReportRepository reports;
    @Autowired ModerationActionRepository actions;
    @Autowired UserBlockRepository blocks;
    @Autowired ChatRestrictionRepository restrictions;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatParticipantRepository participants;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatMembershipIntervalRepository intervals;
    @Autowired UserRepository users;
    @Autowired ScheduleRepository schedules;
    @Autowired RecommendJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestEntityManager em;
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    ApplicationEventPublisher publisher=mock(ApplicationEventPublisher.class);
    ModerationService service;
    ChatModerationGuard guard;
    ChatMessageService chat;
    ChatRoomAccessService access;
    Long alice,bob,carol,outsider,room;
    @BeforeEach void fixture() {
        LocationCryptoProperties properties=new LocationCryptoProperties();
        properties.setEnabled(true); properties.setActiveKid("synthetic");
        properties.setKeys("synthetic:"+Base64.getEncoder().encodeToString(new byte[32]));
        PayloadCipher cipher=new PayloadCipher(properties,mapper); cipher.init();
        service=new ModerationService(reports,actions,blocks,restrictions,messages,users,schedules,jobs,cipher,mapper,jdbc,publisher);
        guard=new ChatModerationGuard(blocks,restrictions,messages,new ChatContentPolicy(""),mapper);
        access=new ChatRoomAccessService(rooms,participants,intervals);
        chat=new ChatMessageService(messages,participants,new ChatProperties(),access,guard);
        alice=user("synthetic-alice"); bob=user("synthetic-bob"); carol=user("synthetic-carol"); outsider=user("synthetic-outsider");
        room=rooms.save(new ChatRoom(99000L,alice,"synthetic room",OffsetDateTime.now().plusDays(1))).getRoomId();
        for (Long user:List.of(alice,bob,carol)) {
            participants.save(new ChatParticipant(room,user,ChatParticipant.Role.MEMBER));
            access.openInterval(room,user,0L);
        }
        em.flush();
    }
    Long user(String name) { return users.save(User.builder().nickname(name).authProvider(AuthProvider.EMAIL).build()).getId(); }
    ReportRequest report(long seq,UUID key) {
        return new ReportRequest(key,ModerationReport.ContentType.CHAT_MESSAGE,ModerationReport.Reason.HARASSMENT,
                "synthetic review request",room,seq,null,null);
    }
    MessageResponse send(Long sender,String text) { return chat.send(room,sender,text,null); }

    @Test void reviewSummaryReportsEncryptDescriptionAndPermitOnlyReviewActions() {
        UUID key=UUID.randomUUID();
        ReportRequest input=new ReportRequest(key,ModerationReport.ContentType.REVIEW_SUMMARY,
                ModerationReport.Reason.INACCURATE,"synthetic reporter explanation",null,null,null,null);
        var submission=service.submit(alice,input);
        UUID id=submission.receipt().reportId();
        assertThat(submission.created()).isTrue();
        assertThat(service.submit(alice,input).created()).isFalse();
        em.flush(); em.clear();
        ModerationReport row=reports.findById(id).orElseThrow();
        assertThat(row.getContentType()).isEqualTo(ModerationReport.ContentType.REVIEW_SUMMARY);
        assertThat(row.getDescription()).contains("\"ct\"").doesNotContain("synthetic reporter explanation");
        assertThat(row.getReportedUserId()).isNull(); assertThat(row.getMessageId()).isNull();
        assertThat(service.detail(id).description()).isEqualTo("synthetic reporter explanation");
        assertThat(service.detail(id).currentMessage()).isNull();
        assertThat(service.act(id,"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.REVIEW,null)).status())
                .isEqualTo(ModerationReport.Status.IN_REVIEW);
        for (ModerationAction.Action action:List.of(ModerationAction.Action.HIDE_CHAT_MESSAGE,
                ModerationAction.Action.RESTRICT_CHAT,ModerationAction.Action.LIFT_CHAT_RESTRICTION)) {
            assertThatThrownBy(()->service.act(id,"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),action,
                    action==ModerationAction.Action.RESTRICT_CHAT ? 24 : null))).isInstanceOf(CustomException.class);
        }
        var resolved=service.act(id,"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.RESOLVE,null));
        assertThat(resolved.status()).isEqualTo(ModerationReport.Status.ACTIONED);
        assertThat(actions.findByReportIdOrderByCreatedAtAsc(id)).hasSize(2);
        assertThat(messages.count()).isZero(); assertThat(restrictions.count()).isZero();
        UUID second=service.submit(bob,new ReportRequest(UUID.randomUUID(),ModerationReport.ContentType.REVIEW_SUMMARY,
                ModerationReport.Reason.OTHER,"synthetic other explanation",null,null,null,null)).receipt().reportId();
        assertThat(service.act(second,"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.DISMISS,null)).status())
                .isEqualTo(ModerationReport.Status.DISMISSED);
    }

    @Test void reportIsOwnedEncryptedIdempotentAndQuotaCannotFailOpen() {
        var message=send(bob,"synthetic message"); UUID key=UUID.randomUUID();
        var first=service.submit(alice,report(message.seq(),key));
        assertThat(first.created()).isTrue();
        assertThat(service.submit(alice,report(message.seq(),key)).created()).isFalse();
        assertThat(reports.count()).isEqualTo(1);
        ModerationReport row=reports.findById(first.receipt().reportId()).orElseThrow();
        assertThat(row.getDescription()).doesNotContain("synthetic review").contains("\"ct\"");
        assertThat(service.detail(row.getReportId()).description()).isEqualTo("synthetic review request");
        assertThat(service.ownReports(bob)).isEmpty();
        assertThatThrownBy(()->service.submit(outsider,report(message.seq(),UUID.randomUUID())))
                .isInstanceOf(CustomException.class).extracting(e->((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.MODERATION_NOT_FOUND);
        ReportRequest changed=new ReportRequest(key,ModerationReport.ContentType.CHAT_MESSAGE,ModerationReport.Reason.SPAM,
                "changed",room,message.seq(),null,null);
        assertThatThrownBy(()->service.submit(alice,changed)).isInstanceOf(CustomException.class);
        service.submit(alice,report(message.seq(),UUID.randomUUID()));
        service.submit(alice,report(message.seq(),UUID.randomUUID()));
        assertThatThrownBy(()->service.submit(alice,report(message.seq(),UUID.randomUUID())))
                .isInstanceOf(CustomException.class).extracting(e->((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
        assertThat(service.submit(alice,report(message.seq(),key)).created()).isFalse();
    }

    @Test void blockingFiltersDatabaseHistoryUnreadPreviewAndEachStompRecipient() throws Exception {
        var b=send(bob,"synthetic bob"); var c=send(carol,"synthetic carol");
        service.block(alice,bob); service.block(alice,bob); em.flush();
        assertThat(blocks.count()).isEqualTo(1);
        assertThat(service.ownBlocks(alice)).singleElement().satisfies(v->{
            assertThat(v.blockedUserId()).isEqualTo(bob); assertThat(v.nickname()).isEqualTo("synthetic-bob"); });
        assertThat(chat.getHistory(room,alice,null,1).messages()).extracting(MessageResponse::seq).containsExactly(c.seq());
        assertThat(chat.getHistory(room,alice,c.seq(),20).messages()).isEmpty();
        assertThat(chat.getUnread(room,alice).unreadCount()).isEqualTo(1);
        assertThat(new ChatRoomService(rooms,participants,messages,schedules,new ChatProperties(),access,guard)
                .listMyRooms(alice)).singleElement().satisfies(r->assertThat(r.lastMessage()).isEqualTo("synthetic carol"));
        byte[] event=mapper.writeValueAsBytes(ChatEventEnvelope.message(b));
        assertThat(guard.mayDeliver(alice,room,event)).isFalse();
        assertThat(guard.mayDeliver(carol,room,event)).isTrue();
        assertThat(guard.mayDeliver(bob,room,event)).isTrue();
        for (var actorEvent:List.of(ChatEventEnvelope.read(room,bob,1),ChatEventEnvelope.typing(room,bob,true),ChatEventEnvelope.presence(room,bob,true))) {
            assertThat(guard.mayDeliver(alice,room,actorEvent)).isFalse();
            assertThat(guard.mayDeliver(carol,room,actorEvent)).isTrue();
        }
        var a=send(alice,"synthetic alice");
        assertThat(guard.mayDeliver(bob,room,ChatEventEnvelope.message(a))).isFalse();
        assertThat(chat.getHistory(room,bob,null,20).messages()).extracting(MessageResponse::senderId).doesNotContain(alice);
        // Blocking does not obstruct reporting the already read abusive message.
        assertThat(service.submit(alice,report(b.seq(),UUID.randomUUID())).created()).isTrue();
        service.unblock(alice,bob); em.flush();
        assertThat(guard.mayDeliver(alice,room,event)).isTrue();
    }

    @Test void blockCannotProbeOrBlockSelfOrUnsharedUser() {
        assertThatThrownBy(()->service.block(alice,alice)).isInstanceOf(CustomException.class);
        assertThatThrownBy(()->service.block(alice,outsider)).isInstanceOf(CustomException.class);
        assertThatThrownBy(()->service.block(alice,Long.MAX_VALUE)).isInstanceOf(CustomException.class);
        assertThat(blocks.count()).isZero();
    }

    @Test void reportUsesMembershipIntervalsIncludingLeaveAndRejoin() {
        var first=send(bob,"first");
        access.closeInterval(room,alice,first.seq());
        var absent=send(bob,"during absence");
        access.openInterval(room,alice,absent.seq());
        var last=send(bob,"after rejoin"); em.flush();
        assertThat(service.submit(alice,report(first.seq(),UUID.randomUUID())).created()).isTrue();
        assertThatThrownBy(()->service.submit(alice,report(absent.seq(),UUID.randomUUID()))).isInstanceOf(CustomException.class);
        assertThat(service.submit(alice,report(last.seq(),UUID.randomUUID())).created()).isTrue();
    }

    @Test void moderationHideIsDurableAuditedIdempotentAndDoesNotEraseOthersMessages() {
        var target=send(bob,"reviewed message"); var other=send(carol,"preserved message");
        var report=service.submit(alice,report(target.seq(),UUID.randomUUID())).receipt();
        UUID action=UUID.randomUUID();
        var request=new ModerationService.ActionRequest(action,ModerationAction.Action.HIDE_CHAT_MESSAGE,null);
        assertThat(service.act(report.reportId(),"operator_17",request).status()).isEqualTo(ModerationReport.Status.ACTIONED);
        assertThat(service.act(report.reportId(),"operator_17",request).status()).isEqualTo(ModerationReport.Status.ACTIONED);
        em.flush(); em.clear();
        assertThat(actions.count()).isEqualTo(1); assertThat(messages.count()).isEqualTo(2);
        assertThat(chat.getHistory(room,alice,null,20).messages()).extracting(MessageResponse::seq).containsExactly(other.seq());
        assertThat(guard.mayDeliver(alice,room,ChatEventEnvelope.message(target))).isFalse();
        assertThat(guard.mayDeliver(alice,room,ChatEventEnvelope.message(other))).isTrue();
        verify(publisher,times(1)).publishEvent(new ModerationService.MessageRemoved(room,target.seq()));
        assertThatThrownBy(()->service.act(report.reportId(),"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.DISMISS,null)))
                .isInstanceOf(CustomException.class);
    }

    @Test void textFilterAndRestrictionApplyToSharedRestAndStompSendPath() {
        long before=messages.count();
        assertThatThrownBy(()->send(bob,"I.WILL.KILL.YOU")).isInstanceOf(CustomException.class)
                .extracting(e->((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.CHAT_CONTENT_REJECTED);
        assertThat(messages.count()).isEqualTo(before);
        var target=send(bob,"ordinary message");
        var report=service.submit(alice,report(target.seq(),UUID.randomUUID())).receipt();
        service.act(report.reportId(),"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.RESTRICT_CHAT,24));
        assertThatThrownBy(()->send(bob,"ordinary followup")).isInstanceOf(CustomException.class)
                .extracting(e->((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.CHAT_RESTRICTED);
        assertThat(send(carol,"still serving").content()).isEqualTo("still serving");
        service.act(report.reportId(),"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.LIFT_CHAT_RESTRICTION,null));
        assertThat(send(bob,"after operator release").content()).isEqualTo("after operator release");
        restrictions.save(new ChatRestriction(bob,OffsetDateTime.now().minusSeconds(1)));
        assertThat(send(bob,"after expiry").content()).isEqualTo("after expiry");
    }

    @Test void legacyUnsafeTextIsMaskedInRestAndNeverDeliveredOrPreviewed() {
        ChatRoom r=rooms.findById(room).orElseThrow();
        ChatMessage m=messages.save(ChatMessage.text(room,r.allocateNextSeq(),bob,"I WILL KILL YOU")); em.flush();
        var history=chat.getHistory(room,alice,null,20).messages().get(0);
        assertThat(history.content()).isEqualTo(ChatContentPolicy.REMOVED_TEXT);
        MessageResponse raw=new MessageResponse(room,m.getSeq(),bob,"TEXT",m.getContent(),null,m.getCreatedAt(),0,null);
        assertThat(guard.mayDeliver(alice,room,ChatEventEnvelope.message(raw))).isFalse();
        assertThat(new ChatRoomService(rooms,participants,messages,schedules,new ChatProperties(),access,guard).listMyRooms(alice))
                .singleElement().satisfies(v->assertThat(v.lastMessage()).isEqualTo(ChatContentPolicy.REMOVED_TEXT));
    }

    @Test void malformedForgedOrErasedQueuedEventsFailClosed() {
        var m=send(bob,"queued message");
        assertThat(guard.mayDeliver(alice,room,"not json")).isFalse();
        assertThat(guard.mayDeliver(alice,room,ChatEventEnvelope.of("UNKNOWN",room,Map.of()))).isFalse();
        assertThat(guard.mayDeliver(alice,room+1,ChatEventEnvelope.message(m))).isFalse();
        var forged=new MessageResponse(room,m.seq(),carol,"TEXT",m.content(),null,m.createdAt(),0,null);
        assertThat(guard.mayDeliver(alice,room,ChatEventEnvelope.message(forged))).isFalse();
        jdbc.update("UPDATE user_service.chat_messages SET content=NULL,sender_id=NULL WHERE room_id=? AND seq=?",room,m.seq());
        em.clear();
        assertThat(guard.mayDeliver(alice,room,ChatEventEnvelope.message(m))).isFalse();
    }

    @Test void retentionScrubsOnlyCompletedNewReportsAndExpiresNewAuditAtExactBoundaries() {
        var target=send(bob,"preserved original chat");
        UUID closed=service.submit(alice,report(target.seq(),UUID.randomUUID())).receipt().reportId();
        UUID open=service.submit(alice,report(target.seq(),UUID.randomUUID())).receipt().reportId();
        service.act(closed,"operator_17",new ModerationService.ActionRequest(UUID.randomUUID(),ModerationAction.Action.DISMISS,null));
        em.flush();
        OffsetDateTime now=OffsetDateTime.parse("2026-09-07T00:00:00Z");
        jdbc.update("UPDATE user_service.moderation_reports SET updated_at=? WHERE report_id=?",now.minusDays(90).plusSeconds(1),closed);
        ModerationRetention retention=new ModerationRetention(jdbc);
        assertThat(retention.prune(now).scrubbed()).isZero();
        jdbc.update("UPDATE user_service.moderation_reports SET updated_at=?",now.minusDays(90));
        assertThat(retention.prune(now).scrubbed()).isEqualTo(1); em.clear();
        assertThat(reports.findById(closed).orElseThrow().getDescription()).isNull();
        assertThat(reports.findById(closed).orElseThrow().getRequestFingerprint()).isNull();
        assertThat(reports.findById(closed).orElseThrow().getRoomId()).isNull();
        assertThat(reports.findById(open).orElseThrow().getDescription()).isNotNull();
        assertThat(messages.findByRoomIdOrderBySeqDesc(room,org.springframework.data.domain.PageRequest.of(0,10)))
                .singleElement().satisfies(m->assertThat(m.getContent()).isEqualTo("preserved original chat"));
        jdbc.update("UPDATE user_service.moderation_actions SET created_at=?",now.minusDays(365));
        jdbc.update("UPDATE user_service.moderation_reports SET updated_at=? WHERE report_id=?",now.minusDays(365),closed);
        var deleted=retention.prune(now);
        assertThat(deleted.actionsDeleted()).isEqualTo(1); assertThat(deleted.reportsDeleted()).isEqualTo(1);
        assertThat(reports.existsById(open)).isTrue(); assertThat(messages.count()).isEqualTo(1);
    }
}
