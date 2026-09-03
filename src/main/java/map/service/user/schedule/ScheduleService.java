package map.service.user.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendJobStore;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.weather.ScheduleWeatherService;
import map.service.user.weather.dto.WeatherSnapshotItem;
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
    private final PayloadCipher payloadCipher;
    private final RecommendJobStore jobStore;
    private final ScheduleWeatherService weatherService;
    private final RecommendService recommendService;

    public ScheduleService(
            DraftStore draftStore,
            ScheduleRepository repository,
            ObjectMapper objectMapper,
            TripStopsAssembler stopsAssembler,
            PayloadCipher payloadCipher,
            RecommendJobStore jobStore,
            ScheduleWeatherService weatherService,
            RecommendService recommendService
    ) {
        this.draftStore = draftStore;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.stopsAssembler = stopsAssembler;
        this.payloadCipher = payloadCipher;
        this.jobStore = jobStore;
        this.weatherService = weatherService;
        this.recommendService = recommendService;
    }

    /**
     * 일정 본문을 묶어 둘 자리 이름. 소유자를 함께 묶어, 한 사람의 본문을
     * 다른 사람의 행에 옮겨 넣어도 열리지 않게 한다. 소유자는 행이 사는 동안
     * 바뀌지 않으므로 묶는 값으로 안전하다.
     */
    private static String payloadAad(Long userId) {
        return PayloadCipher.aad("schedules", "payload",
                userId == null ? null : userId.toString());
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
                payloadCipher.encryptNode(payload, payloadAad(userId)),
                request.transport(),
                request.activeStartHour(),
                request.activeEndHour()
        );
        applyWeatherBaseline(entity, request.jobId(), start, end);
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
                        e.getCreatedAt(),
                        visibleAlert(e)))
                .toList();
        return new ScheduleListResponse(summaries);
    }

    /**
     * 일정에 지역을 새기고 그 시점 예보를 기준선으로 굳힌다.
     *
     * 지역은 추천 요청에만 실려 있고 draft payload 에는 남지 않으므로, 그 요청을
     * 만든 추천 작업에서 되찾아 온다. 지역을 모르는(지역 보존 이전에 만들어진)
     * 작업이면 아무것도 하지 않는다 — 좌표로 지역을 추측해 채우면 엉뚱한 동네
     * 날씨로 "비 온다"고 알리게 된다.
     *
     * 날씨 조회는 best-effort 다. 실패하면 기준선 없이 저장되고, 그 일정은 날씨
     * 감시에서 빠질 뿐 저장 자체는 성공한다.
     */
    private void applyWeatherBaseline(
            ScheduleEntity entity, String jobId, LocalDate start, LocalDate end
    ) {
        jobStore.findRegion(jobId).ifPresent(region -> {
            entity.setRegion(region.province(), region.city());
            List<WeatherSnapshotItem> baseline = weatherService.buildBaseline(
                    region.province(), region.city(), start, end);
            entity.setWeatherBaseline(weatherService.toJson(baseline));
        });
    }

    /**
     * 날씨가 바뀐 일정을 저장된 조건 그대로 다시 추천한다(1클릭 재추천).
     *
     * 저장해 둔 지역·기간·이동수단·활동 시간대를 그대로 써서 새 추천 작업을
     * 띄운다. 사용자가 조건을 다시 입력하지 않아도 되는 것이 이 기능의 요점이다.
     *
     * 재탐색(mode1)이 아니므로 1일 3회 재탐색 한도를 깎지 않는다 — 날씨가
     * 나빠진 것은 사용자의 변심이 아니라 외부 변수이고, 한도를 깎으면 "비가
     * 와서 다시 짜야 하는데 한도가 없다"는 상황이 생긴다.
     *
     * 재사용 캐시는 타지 않는다(createFreshRecommendation). 캐시 키에 날씨가
     * 없어서 조건이 그대로인 이 요청은 반드시 캐시에 맞고, 맞으면 agent 가 아예
     * 돌지 않아 fetch_weather 도 돌지 않는다 — 비 오기 전 코스가 그대로 돌아온다.
     *
     * 걸려 있던 알림을 지우면서 기준선도 지금 예보로 옮긴다. 알림만 지우면
     * 다음 순회가 같은 차이를 또 발견해 30분마다 배너가 되살아난다 — 사용자가
     * 재추천을 눌렀는데도 같은 알림이 반복된다(통합 검증에서 실제로 확인).
     *
     * 지역을 모르는 일정이면 ScheduleReplanUnavailableException.
     * 소유자가 아니거나 없는 일정이면 ScheduleNotFoundException(404).
     */
    @Transactional
    public JobAccepted replan(Long scheduleId, Long userId) {
        ScheduleEntity entity = findOwned(scheduleId, userId);
        if (entity.getProvince() == null || entity.getCity() == null) {
            throw new ScheduleReplanUnavailableException(
                    scheduleId, "지역 정보가 없는 일정");
        }
        if (isPast(entity)) {
            // 이미 다녀왔거나 가지 않기로 한 여행이다. 다시 짜 봐야 쓸 데가
            // 없고, 지나간 기록을 새 코스로 덮는 편이 더 나쁘다.
            throw new ScheduleReplanUnavailableException(
                    scheduleId, "이미 지나간 일정");
        }

        int startHour = entity.getActiveStartHour() != null
                ? entity.getActiveStartHour()
                : TripStopsAssembler.DEFAULT_START_HOUR;
        int endHour = entity.getActiveEndHour() != null
                ? entity.getActiveEndHour()
                : TripStopsAssembler.DEFAULT_END_HOUR;

        RecommendRequest request = new RecommendRequest(
                new DateRange(
                        entity.getDateStart(), entity.getDateEnd(),
                        LocalTime.of(startHour % 24, 0),
                        LocalTime.of(endHour % 24, 0)),
                null,
                null,
                toMobility(entity.getTransport()),
                entity.getProvince(),
                entity.getCity(),
                String.valueOf(scheduleId),
                "init",
                List.of(),
                null);

        JobAccepted accepted = recommendService.createFreshRecommendation(request);
        weatherService.acceptCurrentForecast(entity);
        return accepted;
    }


    /**
     * 걸린 날씨 알림을 사용자가 받아들이지 않고 지운다("이대로 갈래").
     *
     * 알림을 지우는 것으로 끝내면 다음 순회에서 같은 변화를 다시 감지해 또
     * 알림이 붙는다. 그래서 기준선을 지금 예보로 옮긴다 — 사용자가 "이 날씨는
     * 알고 있고 그래도 그대로 간다"고 정한 지점을 기준선이 기억하는 셈이다.
     * 이후 예보가 <b>또</b> 달라지면(비가 그치거나 다른 날이 나빠지면) 새 기준선
     * 대비로 정상 감지된다.
     *
     * 지금 예보를 받지 못하면 기준선은 그대로 두고 알림만 지운다. 기준선을
     * 비우면 그 일정이 감시 대상에서 영영 빠진다(감시 조건이 기준선 보유다).
     * 이 경우 같은 알림이 다시 뜰 수 있지만, 영영 안 뜨는 쪽보다 낫다.
     *
     * 소유자가 아니거나 없는 일정이면 ScheduleNotFoundException(404).
     */
    @Transactional
    public void dismissWeatherAlert(Long scheduleId, Long userId) {
        weatherService.acceptCurrentForecast(findOwned(scheduleId, userId));
    }

    /**
     * 종료일이 지난 일정인지. 지난 일정은 감시도 재추천도 하지 않고, 걸려 있던
     * 알림도 내보내지 않는다 — 다녀온 여행에 "비 예보로 바뀌었어요"가 남아 있으면
     * 사용자가 무엇을 해야 하는지 알 수 없다.
     */
    private static boolean isPast(ScheduleEntity entity) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        return entity.getDateEnd() != null && entity.getDateEnd().isBefore(today);
    }

    /** 응답에 실을 알림. 지난 일정이면 걸려 있어도 싣지 않는다(데이터는 남긴다). */
    private map.service.user.weather.dto.WeatherAlert visibleAlert(
            ScheduleEntity entity
    ) {
        if (isPast(entity)) {
            return null;
        }
        return weatherService.readAlert(entity).orElse(null);
    }

    /**
     * 저장된 일정을 다시 짜기 위한 조건을 꺼낸다(동기 재추천 진입점).
     *
     * 검증은 여기서 끝낸다 — 소유자 확인, 지역 보유, 아직 지나지 않은 일정.
     * trip 도메인은 통과한 사양만 받아 추천을 돌린다.
     *
     * 지역을 모르면 어느 동네로 짜야 할지 알 수 없고(좌표로 추측하면 엉뚱한
     * 동네가 나온다), 지나간 일정은 다시 짤 이유가 없다. 둘 다
     * ScheduleReplanUnavailableException(409).
     *
     * 소유자가 아니거나 없는 일정이면 ScheduleNotFoundException(404).
     */
    @Transactional(readOnly = true)
    public ScheduleReplanSpec replanSpec(Long scheduleId, Long userId) {
        ScheduleEntity entity = findOwned(scheduleId, userId);
        if (entity.getProvince() == null || entity.getCity() == null) {
            throw new ScheduleReplanUnavailableException(
                    scheduleId, "지역 정보가 없는 일정");
        }
        if (isPast(entity)) {
            throw new ScheduleReplanUnavailableException(
                    scheduleId, "이미 지나간 일정");
        }
        // 식별자는 인자 쪽을 쓴다 — 엔티티의 값은 DB 가 채우는 것이라
        // 영속 전 인스턴스에서는 비어 있다.
        return new ScheduleReplanSpec(
                scheduleId,
                entity.getProvince(),
                entity.getCity(),
                entity.getDateStart(),
                entity.getDateEnd(),
                entity.getTransport(),
                entity.getActiveStartHour(),
                entity.getActiveEndHour());
    }

    /**
     * 다시 짜기가 끝났음을 일정에 반영한다 — 기준선을 지금 예보로 옮기고
     * 걸려 있던 알림을 지운다.
     *
     * 추천이 성공한 뒤에만 부른다. 실패한 재추천으로 알림을 지우면 사용자는
     * 바뀐 날씨를 모른 채 옛 코스를 그대로 들고 가게 된다.
     */
    @Transactional
    public void markReplanned(Long scheduleId, Long userId) {
        weatherService.acceptCurrentForecast(findOwned(scheduleId, userId));
    }

    /**
     * 저장된 이동수단 문자열을 추천 요청의 이동수단으로 옮긴다.
     *
     * 값이 없거나 아는 어휘가 아니면 null 이다 — 임의로 도보라고 정하면
     * 자전거로 짜 둔 일정이 다시 짤 때 도보 반경으로 좁아진다.
     */
    private static Mobility toMobility(String transport) {
        if (transport == null) {
            return null;
        }
        for (Mobility m : Mobility.values()) {
            if (m.value().equals(transport)) {
                return m;
            }
        }
        return null;
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
                    payloadCipher.decryptNode(
                            entity.getPayload(), payloadAad(entity.getUserId())),
                    RecommendResponse.class);
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
                timelineStatus,
                visibleAlert(entity));
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
