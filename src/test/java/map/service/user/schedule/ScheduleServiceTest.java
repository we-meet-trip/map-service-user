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
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendService;
import map.service.user.trip.TripStopsAssembler;

/**
 * ScheduleServiceTest — draft 영속화 및 소유자(userId) 기록 검증
 *
 * persist 가 @AuthenticationPrincipal 로 넘어온 userId 를 ScheduleEntity 에
 * 그대로 저장하는지 확인한다.
 *
 * 소유자 없는 저장을 막는 것은 서비스가 아니라 HTTP 진입점의 몫이라
 * (ScheduleController.save 가 401 로 되돌린다) 여기서는 매핑만 본다.
 */
@DisplayName("ScheduleService 단위 테스트")
class ScheduleServiceTest {

    private static final String JOB_ID = "11111111-1111-1111-1111-111111111111";

    private DraftStore draftStore;
    private ScheduleRepository repository;
    private RecommendService recommendService;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        draftStore = mock(DraftStore.class);
        repository = mock(ScheduleRepository.class);
        recommendService = mock(RecommendService.class);
        service = new ScheduleService(draftStore, recommendService, repository,
                mock(ChatRoomRepository.class), mock(ScheduleArrivalRepository.class),
                new ObjectMapper(),
                mock(TripStopsAssembler.class));
        // 저장은 조회와 같은 길로 초안을 찾는다(초안이 없으면 완료 기록으로 내려간다).
        when(recommendService.findDraft(JOB_ID))
                .thenReturn(Optional.of("{\"job_id\":\"" + JOB_ID + "\",\"places\":[]}"));
    }

    private static ScheduleSaveRequest request() {
        return new ScheduleSaveRequest(
                JOB_ID, "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                "walk", 9, 18);
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
    @DisplayName("persist — userId 가 없으면 user_id 를 null 로 매핑(차단은 컨트롤러 몫)")
    void persistWithNullUserIdStoresNull() {
        service.persist(request(), null);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }
}
