package map.service.user.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * 지운 일정을 내보내기만 읽을 수 있는지 (H2).
 *
 * <p>이 시험이 지키는 것은 두 방향이다. 하나는 "내보내기는 흔적을 읽는다" —
 * 지웠다는 사실이 학습 신호라 못 읽으면 그 신호가 통째로 빠진다. 다른 하나는
 * "요청을 처리하는 길에서는 여전히 안 보인다" — 그 조건이 지운 일정으로
 * 채팅방이 열리던 구멍을 막고 있어서, 내보내기를 넣다가 그걸 풀면 안 된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("학습 자료용 일정 조회 (H2)")
class ScheduleExportQueryTest {

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleExportRepository exportRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ScheduleEntity saved(Long userId) {
        ScheduleEntity e = new ScheduleEntity(
                userId, UUID.randomUUID(), "제주 여행",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                objectMapper.createObjectNode().put("timeline_status", "ok"),
                "walk", 10, 18);
        scheduleRepository.saveAndFlush(e);
        return e;
    }

    @Test
    @DisplayName("지운 것도 함께 읽히고, 보통 조회에서는 여전히 안 보인다")
    void exportSeesTombstonesButNormalReadsDoNot() {
        ScheduleEntity live = saved(7L);
        ScheduleEntity gone = saved(7L);
        gone.markDeleted(OffsetDateTime.now());
        scheduleRepository.saveAndFlush(gone);
        entityManager.clear();

        List<ScheduleEntity> page = exportRepository.findPage(0L, 10);

        assertThat(page).extracting(ScheduleEntity::getScheduleId)
                .contains(live.getScheduleId(), gone.getScheduleId());

        // 요청을 처리하는 길은 그대로 막혀 있어야 한다.
        //
        // 여기서 한 번 비우고 본다: 방금 위에서 읽어 들인 것이 같은 작업 단위의
        // 기억에 남아 있으면, 다음 조회가 DB 까지 가지 않고 그 기억에서 답한다 —
        // 조건이 걸린 문장을 아예 안 거치므로 지운 것이 그대로 나온다.
        // 내보내기는 요청 처리와 다른 작업 단위에서 도니 실제로는 섞이지 않지만,
        // 한 작업 단위 안에서 둘을 섞으면 그렇게 된다는 것을 여기 적어 둔다.
        entityManager.clear();
        assertThat(scheduleRepository.findById(gone.getScheduleId())).isEmpty();
    }

    @Test
    @DisplayName("지운 일정의 payload 도 온전히 읽힌다")
    void tombstonedPayloadStillReadable() {
        ScheduleEntity gone = saved(7L);
        gone.markDeleted(OffsetDateTime.now());
        scheduleRepository.saveAndFlush(gone);
        entityManager.clear();

        ScheduleEntity read = exportRepository.findPage(0L, 10).stream()
                .filter(s -> s.getScheduleId().equals(gone.getScheduleId()))
                .findFirst().orElseThrow();

        assertThat(read.getPayload().get("timeline_status").asText()).isEqualTo("ok");
        assertThat(read.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("마지막으로 본 식별자 다음부터 이어 읽는다")
    void keysetPagingContinuesAfterTheLastSeenId() {
        // 뽑는 도중에도 새 일정이 들어오므로 쪽 번호로 세면 경계가 밀린다.
        ScheduleEntity first = saved(7L);
        ScheduleEntity second = saved(7L);
        ScheduleEntity third = saved(7L);
        entityManager.clear();

        assertThat(exportRepository.findPage(0L, 2))
                .extracting(ScheduleEntity::getScheduleId)
                .containsExactly(first.getScheduleId(), second.getScheduleId());
        assertThat(exportRepository.findPage(second.getScheduleId(), 10))
                .extracting(ScheduleEntity::getScheduleId)
                .containsExactly(third.getScheduleId());
    }
}
