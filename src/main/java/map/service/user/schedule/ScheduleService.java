package map.service.user.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import map.service.user.recommend.DraftStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ScheduleService — 일정 영속화 비즈니스 로직
 *
 * 추천 draft 를 일정(ScheduleEntity)으로 변환하여 저장한다.
 * DraftStore 에서 draft JSON 을 읽어 payload 로 보관하고, 저장 성공 시 draft 를 삭제한다.
 *
 * draftStore: DraftStore. draft JSON 조회/삭제.
 * repository: ScheduleRepository. ScheduleEntity 영속화.
 * objectMapper: Jackson ObjectMapper. draft JSON → JsonNode 파싱.
 */
@Service
public class ScheduleService {

    private final DraftStore draftStore;
    private final ScheduleRepository repository;
    private final ObjectMapper objectMapper;

    public ScheduleService(
            DraftStore draftStore,
            ScheduleRepository repository,
            ObjectMapper objectMapper
    ) {
        this.draftStore = draftStore;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * ScheduleSaveRequest 를 ScheduleEntity 로 영속화.
     *
     * 처리 순서:
     * 1) DraftStore.find(jobId) 로 draft JSON 조회. 없으면 ScheduleNotFoundException.
     * 2) ObjectMapper.readTree 로 JsonNode 파싱. 실패 시 IllegalStateException.
     * 3) dateStart/dateEnd 가 null 이면 dateStart 는 오늘, dateEnd 는 dateStart 로 보정.
     * 4) jobId 를 UUID 로 파싱. 실패 시 null 로 두어 jobId 매핑을 건너뛴다.
     * 5) ScheduleEntity 생성 후 repository.save.
     * 6) DraftStore.delete 로 draft 폐기.
     * 7) 저장된 scheduleId 반환.
     *
     * @Transactional 로 묶여 있어 저장 단계 실패 시 draft 삭제는 일어나지 않는다.
     *
     * request: ScheduleSaveRequest. jobId/title/dateStart/dateEnd.
     */
    @Transactional
    public Long persist(ScheduleSaveRequest request) {
        String draftJson = draftStore.find(request.jobId())
                .orElseThrow(() -> new ScheduleNotFoundException(request.jobId()));
        JsonNode payload;
        try {
            payload = objectMapper.readTree(draftJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("draft json parse failed", e);
        }

        LocalDate start = request.dateStart();
        LocalDate end = request.dateEnd();
        if (start == null) {
            start = LocalDate.now();
        }
        if (end == null) {
            end = start;
        }

        UUID jobUuid;
        try {
            jobUuid = UUID.fromString(request.jobId());
        } catch (IllegalArgumentException e) {
            jobUuid = null;
        }

        ScheduleEntity entity = new ScheduleEntity(
                null,
                jobUuid,
                request.title(),
                start,
                end,
                payload
        );
        repository.save(entity);
        draftStore.delete(request.jobId());
        return entity.getScheduleId();
    }
}
