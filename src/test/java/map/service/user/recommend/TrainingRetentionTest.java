package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
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
 * 학습 신호·편집 기록 보존 정리 테스트 (H2).
 *
 * 두 표는 요청이 올 때마다 늘어나기만 하고 줄어들 일이 없다. 그대로 두면 잡
 * 상태를 보는 조회까지 함께 느려지고, 어디를 가려 했는지가 무기한 남는다.
 *
 * 여기서 보는 것은 두 가지다.
 *   - 기한이 지난 것만 지우는가 (최근 것을 함께 쓸어가면 학습 자료가 사라진다)
 *   - 한 번에 지우는 양이 끊기는가 (한 문장으로 다 지우면 잠금을 오래 쥔다)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("학습 신호 보존 정리 (H2)")
class TrainingRetentionTest {

    @Autowired
    private RecommendTrainingRepository trainingRepository;

    @Autowired
    private RecommendEditRepository editRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SIGNAL = "{\"schema_version\":1,\"path\":\"select\"}";

    @BeforeEach
    void clear() {
        trainingRepository.deleteAll();
        editRepository.deleteAll();
        entityManager.flush();
    }

    /** created_at 은 @CreationTimestamp 로 채워지므로, 넣은 뒤 직접 되돌린다. */
    private void ageTraining(UUID jobId, int daysAgo) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE user_service.recommend_training "
                        + "SET created_at = :t WHERE job_id = :id")
                .setParameter("t", OffsetDateTime.now().minusDays(daysAgo))
                .setParameter("id", jobId)
                .executeUpdate();
    }

    private UUID insertSignal() throws Exception {
        UUID id = UUID.randomUUID();
        trainingRepository.saveAndFlush(new RecommendTrainingEntity(
                id, 1, objectMapper.readTree(SIGNAL)));
        return id;
    }

    @Test
    @DisplayName("기한이 지난 것만 지운다")
    void deletesOnlyExpired() throws Exception {
        UUID old = insertSignal();
        UUID recent = insertSignal();
        entityManager.flush();
        ageTraining(old, 200);
        ageTraining(recent, 10);
        entityManager.clear();

        int deleted = trainingRepository.deleteOlderThan(
                OffsetDateTime.now().minusDays(180), 1000);
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(trainingRepository.findById(old)).isEmpty();
        // 최근 것을 함께 쓸어가면 학습 자료가 사라진다.
        assertThat(trainingRepository.findById(recent)).isPresent();
    }

    @Test
    @DisplayName("한 번에 지우는 양이 끊긴다")
    void respectsBatchLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            ageTraining(insertSignal(), 200);
        }
        entityManager.flush();
        entityManager.clear();

        int first = trainingRepository.deleteOlderThan(
                OffsetDateTime.now().minusDays(180), 2);
        entityManager.flush();

        assertThat(first).isEqualTo(2);
        assertThat(trainingRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("지울 것이 없으면 0 을 돌려준다")
    void noopWhenNothingExpired() throws Exception {
        insertSignal();
        entityManager.flush();
        entityManager.clear();

        assertThat(trainingRepository.deleteOlderThan(
                OffsetDateTime.now().minusDays(180), 1000)).isZero();
    }

    @Test
    @DisplayName("편집 기록도 같은 기준으로 지운다")
    void sweepsEditsToo() throws Exception {
        UUID jobId = UUID.randomUUID();
        editRepository.saveAndFlush(new RecommendEditEntity(
                jobId, 1, 7L, null,
                objectMapper.readTree("{\"places\":[]}"),
                objectMapper.readTree("{\"places\":[]}")));
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE user_service.recommend_edits "
                        + "SET created_at = :t WHERE job_id = :id")
                .setParameter("t", OffsetDateTime.now().minusDays(200))
                .setParameter("id", jobId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        assertThat(editRepository.deleteOlderThan(
                OffsetDateTime.now().minusDays(180), 1000)).isEqualTo(1);
    }
}
