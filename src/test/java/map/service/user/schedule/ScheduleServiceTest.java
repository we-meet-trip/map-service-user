package map.service.user.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import map.service.user.recommend.DraftStore;

/**
 * ScheduleServiceTest — draft 영속화 및 소유자(userId) 기록 검증
 *
 * persist 가 @AuthenticationPrincipal 로 넘어온 userId 를 ScheduleEntity 에
 * 그대로 저장하는지, 익명(null) 인 경우 user_id 를 null 로 저장하여 현행 동작을
 * 보존하는지 확인한다.
 */
@DisplayName("ScheduleService 단위 테스트")
class ScheduleServiceTest {

    private static final String JOB_ID = "11111111-1111-1111-1111-111111111111";

    private DraftStore draftStore;
    private ScheduleRepository repository;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        draftStore = mock(DraftStore.class);
        repository = mock(ScheduleRepository.class);
        service = new ScheduleService(draftStore, repository, new ObjectMapper());
        when(draftStore.find(JOB_ID))
                .thenReturn(Optional.of("{\"job_id\":\"" + JOB_ID + "\",\"places\":[]}"));
    }

    private static ScheduleSaveRequest request() {
        return new ScheduleSaveRequest(
                JOB_ID, "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7));
    }

    @Test
    @DisplayName("persist — 인증된 userId 를 ScheduleEntity 에 저장")
    void persistStoresAuthenticatedUserId() {
        service.persist(request(), 42L);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
        verify(draftStore).delete(JOB_ID);
    }

    @Test
    @DisplayName("persist — 익명(userId=null) 은 user_id 를 null 로 저장(현행 동작 보존)")
    void persistWithNullUserIdStoresNull() {
        service.persist(request(), null);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }
}
