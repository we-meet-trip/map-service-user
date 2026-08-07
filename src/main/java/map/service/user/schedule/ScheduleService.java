package map.service.user.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.schedule.dto.ScheduleDetailResponse;
import map.service.user.schedule.dto.ScheduleListResponse;
import map.service.user.schedule.dto.ScheduleSummary;
import map.service.user.trip.TripGenerationException;
import map.service.user.trip.TripStopsAssembler;
import map.service.user.trip.dto.TripStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * stopsAssembler: TripStopsAssembler. 저장된 draft 를 상세 조회 응답의
 *                 방문지 목록으로 접는다 — 생성 직후 화면과 같은 조립기.
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final DraftStore draftStore;
    private final ScheduleRepository repository;
    private final ObjectMapper objectMapper;
    private final TripStopsAssembler stopsAssembler;

    public ScheduleService(
            DraftStore draftStore,
            ScheduleRepository repository,
            ObjectMapper objectMapper,
            TripStopsAssembler stopsAssembler
    ) {
        this.draftStore = draftStore;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.stopsAssembler = stopsAssembler;
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
     * userId: 소유자 식별자. 컨트롤러의 @AuthenticationPrincipal 로 주입된 값으로,
     *         토큰 부재/익명 시 null 이며 이때 user_id 컬럼은 null 로 저장된다
     *         (auth.enforced=false + 토큰 부재 시 현행 동작 보존).
     */
    @Transactional
    public Long persist(ScheduleSaveRequest request, Long userId) {
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
                userId,
                jobUuid,
                request.title(),
                start,
                end,
                payload,
                request.transport(),
                request.activeStartHour(),
                request.activeEndHour()
        );
        repository.save(entity);
        draftStore.delete(request.jobId());
        return entity.getScheduleId();
    }

    /**
     * 소유자의 일정 목록을 시작일 오름차순으로 반환한다.
     *
     * 토큰이 없으면(userId == null) 빈 목록을 돌려준다. 소유자 없이 저장된
     * 행들은 서로를 구분할 근거가 없어서, 그것을 "익명의 목록"으로 묶어 주면
     * 토큰 없는 누구에게나 남이 저장한 일정이 그대로 보이고 삭제까지 열린다.
     * 저장은 지금처럼 토큰 없이도 되지만, 다시 꺼내 보려면 로그인이 필요하다.
     *
     * payload 는 펼치지 않는다 — 목록에 필요한 건 제목과 기간뿐이고, 건마다
     * 방문지를 조립하면 도로 경로 조회까지 건수만큼 일어난다.
     */
    @Transactional(readOnly = true)
    public ScheduleListResponse list(Long userId) {
        if (userId == null) {
            return new ScheduleListResponse(List.of());
        }
        List<ScheduleEntity> rows =
                repository.findByUserIdOrderByDateStartAsc(userId);
        List<ScheduleSummary> summaries = rows.stream()
                .map(e -> new ScheduleSummary(
                        e.getScheduleId(),
                        e.getTitle(),
                        e.getDateStart(),
                        e.getDateEnd(),
                        e.getCreatedAt()))
                .toList();
        return new ScheduleListResponse(summaries);
    }

    /**
     * 일정 1건을 client 결과 화면과 같은 형태로 조립해 반환한다.
     *
     * 소유자가 아니거나 없는 일정이면 ScheduleNotFoundException(404) 이다.
     * "권한 없음"을 따로 알리지 않는 이유는, 403 과 404 를 구분하면 남의
     * 일정이 존재한다는 사실 자체가 새어 나가기 때문이다.
     *
     * 저장 당시 메타(이동수단·활동 시간대)가 없으면 기본 시간대로 조립한다.
     * 그때는 방문 시각이 생성 직후와 달라질 수 있으나, 없는 정보를 지어내는
     * 대신 일정을 열 수 있게 하는 쪽을 택한다.
     *
     * payload 가 실패한 추천이거나 방문지가 없으면 조립기가 예외를 던지므로,
     * 그 경우는 빈 stops 로 응답한다 — 저장은 됐지만 그릴 것이 없는 상태를
     * 오류가 아니라 빈 화면으로 보여준다.
     *
     * 트랜잭션으로 감싸지 않는다. 조립 과정에 도로 경로를 받아오는 외부 왕복이
     * 들어 있어서, 트랜잭션 안에서 하면 그 응답을 기다리는 내내 DB 커넥션을
     * 물고 있게 된다. 읽기는 한 번뿐이고 지연 로딩 관계도 없어 트랜잭션이
     * 필요하지 않다.
     */
    public ScheduleDetailResponse detail(Long scheduleId, Long userId) {
        return toDetail(findOwned(scheduleId, userId));
    }

    /**
     * 일정을 따라가기 시작했다고 기록하고, 상세를 그대로 돌려준다.
     *
     * 시작 시각은 처음 한 번만 새긴다. 이미 시작한 일정을 다시 열어도 값을
     * 덮지 않으므로 이 호출은 몇 번을 해도 결과가 같다 — 화면이 재시도하거나
     * 사용자가 뒤로 갔다 다시 들어와도 안전하다.
     *
     * 상세를 함께 돌려주는 이유는, 시작 화면이 필요로 하는 방문지·이동 카드·
     * 도로 경로가 상세 조회에서만 오기 때문이다. 시작과 조회를 따로 부르게 하면
     * 왕복이 두 번이 되고 그 사이에 화면이 빈 채로 남는다.
     *
     * 트랜잭션으로 감싸지 않는다. 상세 조립에 외부 왕복이 들어 있어 그 시간
     * 내내 DB 커넥션을 물게 되기 때문이다. 시작 기록은 repository.save 가
     * 자체 트랜잭션으로 커밋한다.
     */
    public ScheduleDetailResponse start(Long scheduleId, Long userId) {
        ScheduleEntity entity = findOwned(scheduleId, userId);
        if (entity.markStarted(OffsetDateTime.now())) {
            repository.save(entity);
            log.info("schedule started schedule_id={}", scheduleId);
        }
        return toDetail(entity);
    }

    /**
     * 일정 엔티티를 상세 응답으로 접는다. 조회와 시작이 같은 화면을 만들도록
     * 조립을 한 곳에 둔다.
     */
    private ScheduleDetailResponse toDetail(ScheduleEntity entity) {
        int startHour = entity.getActiveStartHour() != null
                ? entity.getActiveStartHour()
                : TripStopsAssembler.DEFAULT_START_HOUR;
        int endHour = entity.getActiveEndHour() != null
                ? entity.getActiveEndHour()
                : TripStopsAssembler.DEFAULT_END_HOUR;

        List<TripStop> stops;
        List<String> warnings = null;
        String timelineStatus = null;
        try {
            RecommendResponse draft = objectMapper.treeToValue(
                    entity.getPayload(), RecommendResponse.class);
            stops = stopsAssembler.assemble(
                    draft, entity.getTransport(), startHour, endHour);
            // 조립에 성공한 경우에만 싣는다 — 그릴 것이 없는 빈 화면에
            // 생성 당시 안내만 남으면 무엇에 대한 경고인지 알 수 없다.
            warnings = draft.warnings();
            timelineStatus = draft.timelineStatus();
        } catch (JsonProcessingException | TripGenerationException e) {
            log.warn("schedule detail has no renderable stops schedule_id={} reason={}",
                    entity.getScheduleId(), e.getMessage());
            stops = List.of();
        }

        return new ScheduleDetailResponse(
                entity.getScheduleId(),
                entity.getJobId() != null ? entity.getJobId().toString() : null,
                entity.getTitle(),
                entity.getDateStart(),
                entity.getDateEnd(),
                entity.getTransport(),
                TripStopsAssembler.totalDurationMinutes(stops),
                stops,
                entity.getCreatedAt(),
                entity.getStartedAt(),
                warnings,
                timelineStatus);
    }

    /**
     * 일정 1건을 삭제한다. 소유자가 아니거나 없으면 404.
     *
     * 저장된 draft 스냅샷도 함께 사라진다 — 일정 밖에서 그 payload 를
     * 참조하는 곳은 없다.
     */
    @Transactional
    public void delete(Long scheduleId, Long userId) {
        repository.delete(findOwned(scheduleId, userId));
    }

    /**
     * 소유자 조건을 붙여 일정을 찾는다. 없으면 404 예외.
     *
     * 토큰이 없으면 조회 자체를 하지 않는다 — 소유자 없는 행은 누가 저장한
     * 것인지 구분할 수 없어서 열어 주는 순간 전원 공용이 된다.
     */
    private ScheduleEntity findOwned(Long scheduleId, Long userId) {
        if (userId == null) {
            // 소유자 없는 행을 토큰 없이 열어 주면 식별자만 바꿔 가며 남의
            // 일정을 읽고 지울 수 있다. 존재 여부도 알리지 않는다.
            throw new SavedScheduleNotFoundException(scheduleId);
        }
        return repository.findByScheduleIdAndUserId(scheduleId, userId)
                .orElseThrow(() -> new SavedScheduleNotFoundException(scheduleId));
    }
}
