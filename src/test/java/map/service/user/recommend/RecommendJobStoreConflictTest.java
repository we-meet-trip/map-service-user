package map.service.user.recommend;

import map.service.user.global.crypto.TestPayloadCiphers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * RecommendJobStoreConflictTest — 두 스레드가 같은 작업 행을 동시에 쓸 때의 동작 검증
 *
 * 작업 행은 요청을 받은 쪽과 완료 이벤트를 받은 쪽 두 갈래에서 만들어진다. 작업이
 * 즉시 실패하면 두 쪽이 겹치는데, 실제 동시 실행은 테스트로 재현하기 어려우므로
 * 저장소가 기본키 충돌을 알리는 상황을 흉내 내어 그 뒤 처리를 확인한다.
 */
@DisplayName("RecommendJobStore 동시 기록 처리")
class RecommendJobStoreConflictTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("완료 기록이 키 충돌로 밀리면 갱신으로 다시 시도해 상태를 남긴다")
    void markFinishedRetriesAsUpdateOnConflict() {
        RecommendJobRepository repository = mock(RecommendJobRepository.class);
        RecommendTrainingRepository trainingRepository = mock(RecommendTrainingRepository.class);
        RecommendEditRepository editRepository = mock(RecommendEditRepository.class);
        UUID jobId = UUID.randomUUID();
        RecommendJobEntity existing = new RecommendJobEntity(
                jobId, "sched-1", "in_progress", null, null, null);

        // 처음에는 행이 없다고 보고 넣으려다 충돌하고, 그 사이 상대가 넣은 행이 보인다.
        when(repository.findById(jobId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(RecommendJobEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenReturn(existing);

        RecommendJobStore store = new RecommendJobStore(repository, trainingRepository,
                editRepository, objectMapper, TestPayloadCiphers.enabled(),
                org.mockito.Mockito.mock(RecommendEditRequestRepository.class),
                org.mockito.Mockito.mock(RecommendCancellationRepository.class), TestJobOwners.active());
        store.markFinished(jobId.toString(), "failed", "{\"status\":\"failed\"}");

        ArgumentCaptor<RecommendJobEntity> saved =
                ArgumentCaptor.forClass(RecommendJobEntity.class);
        verify(repository, times(2)).save(saved.capture());
        RecommendJobEntity last = saved.getAllValues().get(1);
        assertThat(last.getStatus()).isEqualTo("failed");
        assertThat(last.getFinishedAt()).isNotNull();
        // 두 번째 시도는 기존 행을 갱신한 것이라 최초 기록의 일정 식별자가 남는다.
        assertThat(last.getScheduleId()).isEqualTo("sched-1");
    }

    @Test
    @DisplayName("재시도까지 실패하면 예외를 밖으로 내보내지 않는다")
    void markFinishedSwallowsRepeatedFailure() {
        RecommendJobRepository repository = mock(RecommendJobRepository.class);
        RecommendTrainingRepository trainingRepository = mock(RecommendTrainingRepository.class);
        RecommendEditRepository editRepository = mock(RecommendEditRepository.class);
        UUID jobId = UUID.randomUUID();
        when(repository.findById(jobId)).thenReturn(Optional.empty());
        when(repository.save(any(RecommendJobEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        RecommendJobStore store = new RecommendJobStore(repository, trainingRepository,
                editRepository, objectMapper, TestPayloadCiphers.enabled(),
                org.mockito.Mockito.mock(RecommendEditRequestRepository.class),
                org.mockito.Mockito.mock(RecommendCancellationRepository.class), TestJobOwners.active());
        store.markFinished(jobId.toString(), "done", "{}");

        verify(repository, times(2)).save(any(RecommendJobEntity.class));
    }

    @Test
    @DisplayName("이미 있는 작업에는 최초 기록을 다시 쓰지 않는다")
    void insertInProgressSkipsExistingRow() {
        RecommendJobRepository repository = mock(RecommendJobRepository.class);
        RecommendTrainingRepository trainingRepository = mock(RecommendTrainingRepository.class);
        RecommendEditRepository editRepository = mock(RecommendEditRepository.class);
        UUID jobId = UUID.randomUUID();
        when(repository.existsById(jobId)).thenReturn(true);

        RecommendJobStore store = new RecommendJobStore(repository, trainingRepository,
                editRepository, objectMapper, TestPayloadCiphers.enabled(),
                org.mockito.Mockito.mock(RecommendEditRequestRepository.class),
                org.mockito.Mockito.mock(RecommendCancellationRepository.class), TestJobOwners.active());
        store.insertInProgress(jobId.toString(), "sched-2");

        verify(repository, times(0)).save(any(RecommendJobEntity.class));
    }
}
