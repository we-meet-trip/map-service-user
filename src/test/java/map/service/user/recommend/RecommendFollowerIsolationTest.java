package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import map.service.user.global.crypto.LocationSeal;
import map.service.user.global.crypto.TestPayloadCiphers;
import map.service.user.policy.AiConsentService;
import map.service.user.recommend.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/** Actual encrypted H2 jobs/edit transactions and service/consumer/cache code.
 * Redis primitives alone are replaced; no provider, network or production DB.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RecommendFollowerIsolationTest {
    @Autowired RecommendCancellationRepository cancellations;
    @Autowired RecommendJobRepository jobs;
    @Autowired RecommendEditRepository edits;
    @Autowired RecommendTrainingRepository training;
    @Autowired RecommendEditRequestRepository receipts;
    @Autowired TestEntityManager em;
    final ObjectMapper mapper = new ObjectMapper();
    RecommendJobStore store;
    ReuseCacheStore cache;
    RecommendService service;
    RecommendJobsConsumer consumer;
    AgentClient agent;
    RedisPrimitives redis;

    @BeforeEach void setup() {
        var cipher = TestPayloadCiphers.enabled();
        store = spy(new RecommendJobStore(jobs, training, edits, mapper, cipher, receipts, cancellations, TestJobOwners.active()));
        redis = new RedisPrimitives();
        cache = new ReuseCacheStore(redis.template, 604800, 3600, cipher, 600);
        agent = mock(AgentClient.class);
        var profiles = mock(ProfileThemeProvider.class);
        when(profiles.themesFor(any())).thenReturn(List.of());
        var drafts = mock(DraftStore.class);
        service = new RecommendService(agent, drafts, mapper, mock(ResearchLimitService.class), store,
                cache, new RecommendCacheKey(50000, 60), profiles, Runnable::run, 3,
                mock(map.service.user.schedule.ScheduleRepository.class), mock(AiConsentService.class));
        consumer = new RecommendJobsConsumer(drafts, store, cache, mock(RedisConnectionFactory.class),
                "test-completions", "test-group", "test-consumer", "test-dlq", 3, 20, 60000, 8,
                cipher, mock(LocationSeal.class));
        StringRedisTemplate streams = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(consumer, "streamsTemplate", streams);
    }

    RecommendRequest request() {
        return new RecommendRequest(new DateRange(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10),
                LocalTime.of(9, 0), LocalTime.of(18, 0)), 50000, List.of("역사"), Mobility.WALK,
                "서울특별시", "종로구", null, "init", List.of(), null);
    }

    String payload(String id, String name) {
        return "{\"job_id\":\"" + id + "\",\"status\":\"done\",\"places\":[{\"place_id\":0,"
                + "\"name\":\"" + name + "\",\"address\":\"서울\",\"lat\":37.5,\"lng\":127.0}],"
                + "\"visit_order\":[0],\"legs\":[]}";
    }

    void complete(String id, String raw) {
        consumer.onMessage(StreamRecords.mapBacked(Map.of("job_id", id, "status", "done", "payload", raw))
                .withStreamKey("test-completions").withId(RecordId.of("1-1")));
        em.flush(); em.clear();
    }

    @Test void delayedFollowerReceivesWorkerOriginalAfterProducerOwnerEdits() throws Exception {
        String producer = UUID.randomUUID().toString();
        when(agent.requestRecommend(any())).thenReturn(new JobAccepted(producer, "in_progress", 3));
        assertThat(service.createRecommendationDetailed(request(), 7L).accepted().jobId()).isEqualTo(producer);
        String follower = service.createRecommendationDetailed(request(), 8L).accepted().jobId();
        assertThat(follower).isNotEqualTo(producer);
        verify(agent, times(1)).requestRecommend(any());
        complete(producer, payload(producer, "공통 원본 장소"));

        var editJson = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(payload(producer, "A만의 개인 편집 장소"));
        editJson.remove(List.of("job_id", "status"));
        EditRequest personal = mapper.treeToValue(editJson, EditRequest.class);
        assertThat(service.applyEdit(producer, personal, 7L, "owner-edit").orElseThrow())
                .contains("A만의 개인 편집 장소");
        em.flush(); em.clear();

        String result = service.findOwnedDraft(follower, 8L).orElseThrow();
        assertThat(result).contains("공통 원본 장소").doesNotContain("A만의 개인 편집 장소");
        assertThat(mapper.readTree(result).path("job_id").asText()).isEqualTo(follower);
        assertThat(store.findFinishedPayload(producer).orElseThrow()).contains("A만의 개인 편집 장소");
        assertThat(store.findFinishedPayload(follower).orElseThrow()).contains("공통 원본 장소");
    }

    void editOwner(String producer) throws Exception {
        var edit = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(payload(producer, "A만의 개인 편집 장소"));
        edit.remove(List.of("job_id", "status"));
        service.applyEdit(producer, mapper.treeToValue(edit, EditRequest.class), 7L, "owner-edit").orElseThrow();
        em.flush(); em.clear();
    }

    String[] pair() {
        String producer = UUID.randomUUID().toString();
        when(agent.requestRecommend(any())).thenReturn(new JobAccepted(producer, "in_progress", 3));
        service.createRecommendationDetailed(request(), 7L);
        return new String[]{producer, service.createRecommendationDetailed(request(), 8L).accepted().jobId()};
    }

    @Test void editBetweenDurableCompletionAndPublicationCannotReplaceWorkerSnapshot() throws Exception {
        String[] ids = pair();
        doAnswer(call -> { Object accepted = call.callRealMethod(); editOwner(ids[0]); return accepted; })
                .when(store).recordWorkerCompletion(eq(ids[0]), anyString(), anyString(), nullable(String.class));
        complete(ids[0], payload(ids[0], "공통 원본 장소"));
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow())
                .contains("공통 원본 장소").doesNotContain("A만의 개인 편집 장소");
    }

    @Test void workerBeforeProducerRegistrationUsesOriginalSnapshot() throws Exception {
        String producer = UUID.randomUUID().toString();
        String[] follower = new String[1];
        when(agent.requestRecommend(any())).thenAnswer(call -> {
            follower[0] = service.createRecommendationDetailed(request(), 8L).accepted().jobId();
            complete(producer, payload(producer, "공통 원본 장소"));
            return new JobAccepted(producer, "in_progress", 3);
        });
        service.createRecommendationDetailed(request(), 7L);
        editOwner(producer);
        assertThat(service.findOwnedDraft(follower[0], 8L).orElseThrow())
                .contains("공통 원본 장소").doesNotContain("A만의 개인 편집 장소");
        verify(agent, times(1)).requestRecommend(any());
    }

    @Test void missingRedisWaitMarkerStillResolvesFromDurableGeneration() {
        String[] ids = pair();
        complete(ids[0], payload(ids[0], "공통 원본 장소"));
        redis.values.remove("recommend:cache-wait:" + ids[1]);
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow()).contains("공통 원본 장소");
        assertThat(store.findWaiting(ids[1])).isEmpty();
        assertThat(jobs.findById(UUID.fromString(ids[1])).orElseThrow().getParentJobId())
                .isEqualTo(UUID.fromString(ids[0]));
    }

    void expire(String id) {
        RecommendJobEntity row = jobs.findById(UUID.fromString(id)).orElseThrow();
        row.setWaiting(row.getWaitingKey(), java.time.OffsetDateTime.now().minusSeconds(1));
        em.flush(); em.clear();
    }

    @Test void lostRedisGenerationBecomesDurableTerminalAtDeadlineOnly() throws Exception {
        String[] ids = pair();
        redis.values.clear();
        assertThat(service.findOwnedDraft(ids[1], 8L)).isEmpty();
        expire(ids[1]);
        String terminal = service.findOwnedDraft(ids[1], 8L).orElseThrow();
        assertThat(mapper.readTree(terminal).path("code").asText()).isEqualTo("generation_failed");
        assertThat(mapper.readTree(terminal).path("retryable").asBoolean()).isFalse();
        assertThat(terminal).doesNotContain(ids[0]);
        assertThat(store.findWaiting(ids[1])).isEmpty();
        assertThat(service.findOwnedDraft(ids[1], 8L)).contains(terminal);
        assertThat(service.findOwnedDraft(ids[0], 7L)).isEmpty();
    }

    @Test void redisOutageDoesNotPreventDurableFollowerExpiry() {
        String[] ids = pair(); expire(ids[1]);
        when(redis.template.opsForValue().get(anyString())).thenThrow(new IllegalStateException("synthetic outage"));
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow()).contains("generation_failed");
        assertThat(store.findFinishedPayload(ids[1])).isPresent();
    }

    @Test void ordinaryRouteAndProducerRemainInProgressWithoutFollowerMetadata() {
        String[] ids = pair();
        String route = UUID.randomUUID().toString();
        store.insertInProgress(route, null, RecommendJobStore.JobOrigin.agent("route").ownedBy(7L));
        redis.values.clear();
        assertThat(service.findOwnedDraft(ids[0], 7L)).isEmpty();
        assertThat(service.findOwnedDraft(route, 7L)).isEmpty();
        assertThat(store.findWaiting(ids[0])).isEmpty();
        assertThat(store.findWaiting(route)).isEmpty();
    }

    @Test void duplicateTerminalCannotReplaceFirstGenerationSnapshot() {
        String[] ids = pair();
        complete(ids[0], payload(ids[0], "공통 원본 장소"));
        complete(ids[0], payload(ids[0], "다른 완료 내용"));
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow())
                .contains("공통 원본 장소").doesNotContain("다른 완료 내용");
    }

    @Test void cancellationAfterCompletionAndLateRedeliveryCannotResurrectPayload() {
        String[] ids = pair();
        complete(ids[0], payload(ids[0], "삭제 대상 원본"));
        cancellations.saveAndFlush(new RecommendCancellation(UUID.fromString(ids[0])));
        assertThat(store.cancelledAmong(java.util.Arrays.asList(ids[0], ids[1], null, "invalid")))
                .containsExactly(ids[0]);
        cache.cancelProducer(ids[0]);
        complete(ids[0], payload(ids[0], "삭제 대상 원본"));
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow())
                .contains("generation_failed").doesNotContain("삭제 대상 원본").doesNotContain(ids[0]);
        assertThat(redis.values.get("recommend:worker-completion:" + ids[0])).isEqualTo("failed");
    }

    @Test void tombstoneWithoutRedisCleanupStillPreventsCopyingDeletedProducerResult() {
        String[] ids = pair(); complete(ids[0], payload(ids[0], "삭제 대상 원본"));
        cancellations.saveAndFlush(new RecommendCancellation(UUID.fromString(ids[0])));
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow())
                .contains("generation_failed").doesNotContain("삭제 대상 원본");
    }

    @Test void generationBodyIsEncryptedAndBoundToTokenIncludingProducerIdentity() {
        String[] ids = pair(); complete(ids[0], payload(ids[0], "공통 원본 장소"));
        String token = store.findWaiting(ids[1]).orElseThrow().key().substring(3);
        String sealed = redis.values.get("recommend:completion:" + token);
        assertThat(sealed).doesNotContain("공통 원본 장소").doesNotContain(ids[0]);
        redis.values.put("recommend:completion:another-generation", sealed);
        assertThatThrownBy(() -> cache.completion("another-generation")).isInstanceOf(IllegalStateException.class);
        // Bare legacy IDs are never dereferenced against the owner's current draft.
        redis.values.put("recommend:completion:legacy", ids[0]);
        assertThat(cache.completion("legacy").orElseThrow().payload()).contains("generation_failed");
    }

    @Test void expiredGenerationCannotConsumeNewerGenerationOrLateCompletion() {
        String[] ids = pair(); redis.values.clear(); expire(ids[1]);
        String failed = service.findOwnedDraft(ids[1], 8L).orElseThrow();
        complete(ids[0], payload(ids[0], "늦은 원본"));
        assertThat(service.findOwnedDraft(ids[1], 8L)).contains(failed);
        assertThat(failed).contains("generation_failed").doesNotContain("늦은 원본");
    }

    @Test void competingTerminalDecisionsReturnTheAlreadyPersistedWinner() {
        String[] ids = pair();
        String original = payload(ids[1], "공통 원본 장소");
        assertThat(store.finishWaiting(ids[1], ids[0], "done", original, ReuseCacheStore.FAILED_COMPLETION))
                .contains(original);
        assertThat(store.finishWaiting(ids[1], null, "failed", ReuseCacheStore.FAILED_COMPLETION, ReuseCacheStore.FAILED_COMPLETION))
                .contains(original);
        assertThat(store.findWaiting(ids[1])).isEmpty();
    }

    @Test void conflictingFailureAfterSuccessfulPgCommitAndRedisOutageCannotWinPublication() throws Exception {
        String[] ids = pair();
        var firstPublication = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(redis.template.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> {
                    String key = call.getArgument(0);
                    if (key.startsWith("recommend:worker-completion:") && firstPublication.getAndSet(false))
                        throw new IllegalStateException("synthetic Redis publication outage");
                    return redis.values.putIfAbsent(key, call.getArgument(1)) == null;
                });
        String original = payload(ids[0], "공통 원본 장소");
        complete(ids[0], original); // PG commit succeeds; Redis fails; original remains unacknowledged.
        assertThat(store.findFinishedPayload(ids[0]).orElseThrow()).contains("공통 원본 장소");
        editOwner(ids[0]);
        consumer.onMessage(StreamRecords.mapBacked(Map.of("job_id", ids[0], "status", "failed",
                "payload", ReuseCacheStore.FAILED_COMPLETION)).withStreamKey("test-completions").withId(RecordId.of("2-1")));
        assertThat(service.findOwnedDraft(ids[1], 8L)).isEmpty();
        complete(ids[0], original); // Reclaim of the identical original event repairs the snapshot.
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow())
                .contains("공통 원본 장소").doesNotContain("A만의 개인 편집 장소").doesNotContain("generation_failed");
        assertThat(store.findFinishedPayload(ids[0]).orElseThrow()).contains("A만의 개인 편집 장소");
    }

    @Test void preUpgradeTerminalWithoutFingerprintCannotAdoptLaterWorkerEvent() {
        String[] ids = pair();
        store.recordCompletion(ids[0], "done", payload(ids[0], "과거 원본"), null);
        complete(ids[0], payload(ids[0], "나중에 도착한 다른 원문"));
        assertThat(jobs.findById(UUID.fromString(ids[0])).orElseThrow().getWorkerCompletionHash()).isNull();
        assertThat(redis.values.get("recommend:worker-completion:" + ids[0])).isNull();
        assertThat(service.findOwnedDraft(ids[1], 8L)).isEmpty();
    }

    @Test @SuppressWarnings("unchecked")
    void dlqReprocessUsesSameFirstTerminalForOriginalDuplicateAndConflictingFailure() throws Exception {
        String[] ids = pair();
        String raw = payload(ids[0], "공통 원본 장소");
        var dlq = new map.service.user.admin.AdminDlqService(mock(RedisConnectionFactory.class),
                mock(DraftStore.class), store, cache, "test-dlq", TestPayloadCiphers.enabled());
        StringRedisTemplate streams = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.StreamOperations<String, Object, Object> ops =
                mock(org.springframework.data.redis.core.StreamOperations.class);
        when(streams.opsForStream()).thenReturn(ops);
        ReflectionTestUtils.setField(dlq, "streamsTemplate", streams);
        var done = org.springframework.data.redis.connection.stream.MapRecord.create("test-dlq",
                Map.<Object, Object>of("job_id", ids[0], "status", "done", "payload", raw)).withId(RecordId.of("1-1"));
        var failed = org.springframework.data.redis.connection.stream.MapRecord.create("test-dlq",
                Map.<Object, Object>of("job_id", ids[0], "status", "failed", "payload", ReuseCacheStore.FAILED_COMPLETION))
                .withId(RecordId.of("2-1"));
        when(ops.range(eq("test-dlq"), any(org.springframework.data.domain.Range.class)))
                .thenReturn(List.of(done), List.of(done), List.of(failed));
        assertThat(dlq.reprocess(List.of("1-1")).succeeded()).isEqualTo(1);
        editOwner(ids[0]);
        assertThat(dlq.reprocess(List.of("1-1")).succeeded()).isEqualTo(1);
        assertThat(dlq.reprocess(List.of("2-1")).succeeded()).isEqualTo(1);
        assertThat(service.findOwnedDraft(ids[1], 8L).orElseThrow())
                .contains("공통 원본 장소").doesNotContain("A만의 개인 편집 장소").doesNotContain("generation_failed");
        assertThat(store.findFinishedPayload(ids[0]).orElseThrow()).contains("A만의 개인 편집 장소");
    }

    static class RedisPrimitives {
        final Map<String, String> values = new HashMap<>();
        final StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") RedisPrimitives() {
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
            when(ops.getAndDelete(anyString())).thenAnswer(c -> values.remove(c.getArgument(0)));
            doAnswer(c -> { values.put(c.getArgument(0), c.getArgument(1)); return null; })
                    .when(ops).set(anyString(), anyString(), any(Duration.class));
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(c -> values.putIfAbsent(c.getArgument(0), c.getArgument(1)) == null);
            when(template.delete(anyString())).thenAnswer(c -> values.remove(c.getArgument(0)) != null);
            when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(c -> {
                RedisScript<?> script = c.getArgument(0);
                List<String> keys = c.getArgument(1);
                Object[] args = (Object[]) c.getRawArguments()[2];
                String key = keys.get(0), token = (String) args[0];
                if (script.getScriptAsString().startsWith("local current")) {
                    values.putIfAbsent(key, token); return values.get(key);
                }
                if (token.equals(values.get(key))) { values.remove(key); return 1L; }
                return 0L;
            });
        }
    }
}
