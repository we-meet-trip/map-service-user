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
 * 도착 기록이 처음 것만 남기는지 (H2).
 *
 * <p>기기는 위치가 들어올 때마다 판정하므로 같은 자리를 여러 번 알려 오는 것이
 * 정상이다. 그때마다 시각을 덮으면 "언제 처음 닿았는가" 가 사라지고, 행이
 * 늘어나면 한 자리를 여러 번 간 것처럼 보인다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("방문지 도착 기록 (H2)")
class ScheduleArrivalTest {

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleArrivalRepository arrivalRepository;

    @Autowired
    private TestEntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long savedSchedule() {
        ScheduleEntity e = new ScheduleEntity(
                7L, UUID.randomUUID(), "제주 여행",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                objectMapper.createObjectNode().put("timeline_status", "ok"),
                "walk", 10, 18);
        scheduleRepository.saveAndFlush(e);
        return e.getScheduleId();
    }

    @Test
    @DisplayName("같은 자리를 여러 번 알려도 한 번만 남는다")
    void repeatedArrivalsCollapseToTheFirst() {
        Long id = savedSchedule();
        OffsetDateTime first = OffsetDateTime.now().minusMinutes(10);

        arrivalRepository.saveAndFlush(
                new ScheduleArrivalEntity(id, 1, 2, first, "ok"));
        entityManager.clear();

        // 같은 열쇠는 이미 있는 것으로 걸린다 — 서비스는 이것을 보고 넘어간다.
        assertThat(arrivalRepository.existsById(new ScheduleArrivalId(id, 1, 2)))
                .isTrue();
        assertThat(arrivalRepository.count()).isEqualTo(1);
        assertThat(arrivalRepository.findAll().get(0).getArrivedAt())
                .isCloseTo(first, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("자리가 다르면 따로 남는다")
    void differentStopsAreSeparateRows() {
        Long id = savedSchedule();
        OffsetDateTime now = OffsetDateTime.now();

        arrivalRepository.saveAndFlush(new ScheduleArrivalEntity(id, 1, 1, now, "ok"));
        arrivalRepository.saveAndFlush(new ScheduleArrivalEntity(id, 1, 2, now, "ok"));
        arrivalRepository.saveAndFlush(new ScheduleArrivalEntity(id, 2, 1, now, "ok"));
        entityManager.clear();

        // 순번은 일차마다 다시 시작한다 — 일차가 열쇠에 있어야 구분된다.
        assertThat(arrivalRepository.count()).isEqualTo(3);
    }

    // 일정이 지워질 때 도착 기록도 함께 사라지는지는 여기서 볼 수 없다.
    // 이 시험이 쓰는 DB 는 엔티티에서 표를 만들어 외래키가 생기지 않는다 —
    // 연쇄는 실제 DB 에만 있으므로 스택을 띄운 검사에서 확인한다.

    private static org.assertj.core.data.TemporalUnitOffset within(
            long value, java.time.temporal.TemporalUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(value, unit);
    }
}
