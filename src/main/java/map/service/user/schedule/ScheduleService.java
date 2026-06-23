package map.service.user.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
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
     *    보정 후 dateStart 가 dateEnd 보다 뒤이면 InvalidScheduleDateException(400).
     * 4) jobId 를 UUID 로 파싱(컨트롤러 @Valid 의 @Pattern 으로 형식 보장).
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
            // 서버 JVM 기본 TZ(예: UTC)에 의존하지 않도록 KST 로 오늘 날짜를 구한다.
            start = LocalDate.now(ZoneId.of("Asia/Seoul"));
        }
        if (end == null) {
            end = start;
        }
        if (start.isAfter(end)) {
            throw new InvalidScheduleDateException(
                    "date_start must be on or before date_end");
        }

        // jobId 는 @Pattern(UUID) 로 검증되어 진입하므로 그대로 파싱한다.
        // (형식 오류는 컨트롤러 @Valid 단계에서 400 으로 거부된다.)
        UUID jobUuid = UUID.fromString(request.jobId());

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
