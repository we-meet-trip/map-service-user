package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
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
 * RecommendJobStoreTest — recommend_jobs PG write-through 저장소 통합 테스트 (H2)
 *
 * insert(in_progress) → markFinished(done) → findFinishedPayload 라운드트립과,
 * 유효하지 않은 UUID/미완료 상태에 대한 best-effort 동작을 검증한다.
 *
 * RecommendJobEntity 는 user_service 스키마에 매핑되므로, @DataJpaTest 의 기본
 * 임베디드 DB 치환을 끄고(application-test.yaml 의 H2 URL + INIT 스키마 생성 사용)
 * user_service 스키마가 존재하는 환경에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("RecommendJobStore 통합 테스트 (H2)")
class RecommendJobStoreTest {

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

    /** 보류 중인 변경을 DB 로 flush 하고 영속성 컨텍스트를 비워 진짜 DB 재조회를 강제한다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("insert→markFinished→findFinishedPayload 라운드트립")
    void roundTrip() throws Exception {
        String jobId = UUID.randomUUID().toString();

        store.insertInProgress(jobId, "sched-9");
        // 진행 중에는 완료 결과가 없다.
        assertThat(store.findFinishedPayload(jobId)).isEmpty();

        store.markFinished(jobId, "done", "{\"status\":\"done\",\"places\":[]}");
        flushAndClear();

        Optional<String> payload = store.findFinishedPayload(jobId);
        assertThat(payload).isPresent();
        assertThat(objectMapper.readTree(payload.get()).get("status").asText())
                .isEqualTo("done");

        RecommendJobEntity saved = repository.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("done");
        assertThat(saved.getScheduleId()).isEqualTo("sched-9");
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("markFinished 가 insert 없이도 완료 행을 생성")
    void markFinishedWithoutInsertCreatesRow() {
        String jobId = UUID.randomUUID().toString();

        store.markFinished(jobId, "failed", "{\"status\":\"failed\"}");
        flushAndClear();

        RecommendJobEntity saved = repository.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("failed");
        assertThat(store.findFinishedPayload(jobId)).isPresent();
    }

    @Test
    @DisplayName("유효하지 않은 UUID — no-op(예외 없음)")
    void invalidUuidIsNoOp() {
        store.insertInProgress("not-a-uuid", "sched-1");
        store.markFinished("also-bad", "done", "{}");

        assertThat(store.findFinishedPayload("still-bad")).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("in_progress 상태는 findFinishedPayload 에서 제외")
    void inProgressNotReturned() {
        String jobId = UUID.randomUUID().toString();
        store.insertInProgress(jobId, null);

        assertThat(store.findFinishedPayload(jobId)).isEmpty();
    }

    @Test
    @DisplayName("완료 기록이 먼저 도착해도 뒤늦은 최초 기록이 그것을 되돌리지 않는다")
    void lateInsertDoesNotRevertFinished() {
        // 작업이 즉시 실패하면 완료 이벤트가 최초 기록보다 먼저 온다.
        String jobId = UUID.randomUUID().toString();
        store.markFinished(jobId, "failed", "{\"status\":\"failed\"}");
        flushAndClear();

        store.insertInProgress(jobId, "sched-late");
        flushAndClear();

        RecommendJobEntity saved = repository.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo("failed");
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getResultPayload()).isNotNull();
    }
}
