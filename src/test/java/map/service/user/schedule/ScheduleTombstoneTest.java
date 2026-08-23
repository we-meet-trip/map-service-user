package map.service.user.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * 지운 표시가 조회를 실제로 막는지 (H2).
 *
 * <p>파생 쿼리마다 조건을 붙이는 방식이면 findById 같은 기본 조회가 그대로
 * 샌다. 실제로 채팅방을 만드는 쪽이 findById 를 쓰고 있어, 그 구멍이 남으면
 * 지운 일정으로 새 방을 열 수 있다. 엔티티 한 곳에서 막는 것이 요점이라
 * "기본 조회까지 막히는가" 를 여기서 못 박는다.
 *
 * <p>정리 문장은 그 제한을 넘어 흔적을 직접 지워야 하므로 함께 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("일정 툼스톤 (H2)")
class ScheduleTombstoneTest {

    @Autowired
    private ScheduleRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ScheduleEntity saved(Long userId) {
        ScheduleEntity e = new ScheduleEntity(
                userId, UUID.randomUUID(), "제주 여행",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                objectMapper.createObjectNode().put("job_id", UUID.randomUUID().toString()),
                "walk", 10, 18);
        repository.saveAndFlush(e);
        return e;
    }

    @Test
    @DisplayName("지운 표시가 붙으면 기본 조회에서도 사라진다")
    void tombstonedRowIsInvisibleEvenToFindById() {
        ScheduleEntity e = saved(7L);
        Long id = e.getScheduleId();

        e.markDeleted(OffsetDateTime.now());
        repository.saveAndFlush(e);
        entityManager.clear();

        // 채팅방을 만드는 쪽이 쓰는 길이다. 여기가 열려 있으면 지운 일정으로
        // 새 방이 열린다.
        assertThat(repository.findById(id)).isEmpty();
        assertThat(repository.findByScheduleIdAndUserId(id, 7L)).isEmpty();
        assertThat(repository.findByUserIdOrderByDateStartAsc(7L)).isEmpty();
    }

    @Test
    @DisplayName("두 번 지워도 처음 시각이 남는다")
    void firstDeletionTimeWins() {
        // 정리하는 쪽이 이 시각을 기준으로 삼는다. 덮으면 정리가 계속 미뤄진다.
        ScheduleEntity e = saved(7L);
        OffsetDateTime first = OffsetDateTime.now().minusDays(3);

        e.markDeleted(first);
        e.markDeleted(OffsetDateTime.now());

        assertThat(e.getDeletedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("기한이 지난 흔적만 정리된다")
    void sweepRemovesOnlyExpiredTombstones() {
        ScheduleEntity live = saved(7L);
        ScheduleEntity fresh = saved(7L);
        ScheduleEntity old = saved(7L);
        fresh.markDeleted(OffsetDateTime.now().minusDays(1));
        old.markDeleted(OffsetDateTime.now().minusDays(120));
        repository.saveAndFlush(fresh);
        repository.saveAndFlush(old);
        entityManager.clear();

        int purged = repository.deleteTombstonedBefore(
                OffsetDateTime.now().minusDays(90), 500);
        entityManager.clear();

        assertThat(purged).isEqualTo(1);
        // 살아 있는 것은 그대로 보이고, 아직 기한 전인 흔적은 여전히 숨어 있다.
        assertThat(repository.findById(live.getScheduleId())).isPresent();
        assertThat(repository.findById(fresh.getScheduleId())).isEmpty();
        assertThat(repository.findById(old.getScheduleId())).isEmpty();
    }
}
