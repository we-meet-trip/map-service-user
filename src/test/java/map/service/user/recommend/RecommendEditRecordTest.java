package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * 초안 수정 기록 테스트 (H2).
 *
 * 사용자가 뺀 장소가 학습에서 가장 값진 신호인데, 예전에는 수정이 초안을
 * 덮어써서 무엇이 빠졌는지 사후에 알 방법이 없었다. 여기서는 그 전후가 실제로
 * 남는지와, 완료 결과까지 함께 맞춰지는지를 본다.
 *
 * 마지막 항목이 중요한 이유: 초안은 Redis 에 있고 수명이 짧다. 만료된 뒤에는
 * PG 의 완료 결과로 답하는데, 그것을 함께 고치지 않으면 수정 이전 결과가
 * 되살아난다 — 사용자가 뺀 장소가 다시 나타난다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("초안 수정 기록 (H2)")
class RecommendEditRecordTest {

    @Autowired
    private RecommendJobRepository repository;

    @Autowired
    private RecommendTrainingRepository trainingRepository;

    @Autowired
    private RecommendEditRepository editRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecommendJobStore store;

    @BeforeEach
    void setUp() {
        store = new RecommendJobStore(repository, trainingRepository, editRepository, objectMapper);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("수정 전후가 남는다")
    void recordsBeforeAndAfter() {
        String jobId = UUID.randomUUID().toString();
        store.insertInProgress(jobId, "sched-1");

        boolean recorded = store.recordEdit(jobId, 7L, null,
                "{\"places\":[{\"name\":\"뺀곳\"}]}", "{\"places\":[]}");
        flushAndClear();

        assertThat(recorded).isTrue();
        RecommendEditEntity saved = editRepository.findAll().get(0);
        assertThat(saved.getJobId()).isEqualTo(UUID.fromString(jobId));
        assertThat(saved.getSeq()).isEqualTo(1);
        assertThat(saved.getActorUserId()).isEqualTo(7L);
        assertThat(saved.getBeforePayload().toString()).contains("뺀곳");
        assertThat(saved.getAfterPayload().toString()).doesNotContain("뺀곳");
    }

    @Test
    @DisplayName("완료 결과도 고친 값으로 맞춰진다")
    void syncsFinishedPayload() {
        String jobId = UUID.randomUUID().toString();
        store.insertInProgress(jobId, "sched-1");
        store.markFinished(jobId, "done", "{\"places\":[{\"name\":\"뺀곳\"}]}");
        flushAndClear();

        store.recordEdit(jobId, 7L, null,
                "{\"places\":[{\"name\":\"뺀곳\"}]}", "{\"places\":[]}");
        flushAndClear();

        // 초안이 만료돼 PG 로 물었을 때 수정 이전 결과가 되살아나면 안 된다.
        assertThat(store.findFinishedPayload(jobId)).isPresent();
        assertThat(store.findFinishedPayload(jobId).get()).doesNotContain("뺀곳");
    }

    @Test
    @DisplayName("순번이 1 부터 차례로 붙는다")
    void seqIncrements() {
        String jobId = UUID.randomUUID().toString();
        store.insertInProgress(jobId, "sched-1");

        store.recordEdit(jobId, 7L, null, "{\"a\":1}", "{\"a\":2}");
        flushAndClear();
        store.recordEdit(jobId, 7L, null, "{\"a\":2}", "{\"a\":3}");
        flushAndClear();

        assertThat(editRepository.findAll())
                .extracting(RecommendEditEntity::getSeq)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("같은 멱등키는 한 번만 기록된다")
    void idempotentByKey() {
        String jobId = UUID.randomUUID().toString();
        store.insertInProgress(jobId, "sched-1");

        boolean first = store.recordEdit(jobId, 7L, "key-1", "{\"a\":1}", "{\"a\":2}");
        flushAndClear();
        boolean second = store.recordEdit(jobId, 7L, "key-1", "{\"a\":1}", "{\"a\":2}");
        flushAndClear();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(editRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("소유자는 만든 사람으로 남고, 모르면 비어 있다")
    void ownerRecordedWhenKnown() {
        String owned = UUID.randomUUID().toString();
        String anonymous = UUID.randomUUID().toString();

        store.insertInProgress(owned, "sched-1",
                RecommendJobStore.JobOrigin.agent("init").ownedBy(42L));
        store.insertInProgress(anonymous, "sched-2",
                RecommendJobStore.JobOrigin.agent("init").ownedBy(null));
        flushAndClear();

        assertThat(store.ownerOf(owned)).isEqualTo(42L);
        // 토큰 없이 만든 잡은 소유자를 모른다. 모른다는 이유로 잠그면 인증을
        // 켜기도 전에 기존 사용자가 막힌다.
        assertThat(store.ownerOf(anonymous)).isNull();
    }

    @Test
    @DisplayName("행을 새로 만든 직후에도 출처가 남는다")
    void originPersistsOnFreshRow() {
        // 캐시로 답하는 경로가 이렇게 동작한다 — 행을 만들고 곧바로 출처를
        // 채운다. 넣은 것이 아직 DB 에 닿지 않은 채 갱신이 돌면 조용히 0 행이
        // 되어 출처가 영영 비어 있게 된다.
        String jobId = UUID.randomUUID().toString();

        store.insertInProgress(jobId, "sched-fresh",
                RecommendJobStore.JobOrigin.cacheHit().ownedBy(11L));
        flushAndClear();

        RecommendJobEntity saved = repository.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getSource()).isEqualTo("cache_hit");
        assertThat(saved.getMode()).isEqualTo("init");
        assertThat(saved.getOwnerUserId()).isEqualTo(11L);
    }
}
