package map.service.user.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import map.service.user.places.ReviewSummaryService;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.schedule.ScheduleReplanSpec;
import map.service.user.schedule.ScheduleService;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.trip.dto.HubWeatherResponse;
import map.service.user.trip.dto.Schedule;
import map.service.user.trip.dto.TripGenerateRequest;
import map.service.user.trip.dto.TripGenerateResponse;
import map.service.user.trip.dto.TripResearchRequest;
import map.service.user.trip.dto.TripRouteRequest;
import map.service.user.trip.dto.TripStop;
import map.service.user.trip.dto.WeatherForecastItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TripService — POST /api/v1/trip/generate 의 동기 facade 오케스트레이션
 *
 * client 의 동기 단발 요청을, 기존 비동기 agent 추천 파이프라인 위에 동기 facade 로 얹는다.
 * 흐름:
 *   1) client 요청 → agent RecommendRequest 변환(TripMapping).
 *   2) AgentClient.requestRecommend → agent /v1/recommend (202 + job_id).
 *   3) DraftStore.find(job_id) 폴링(≤ poll-timeout). draft 는 RecommendJobsConsumer 가
 *      Redis Streams 결과를 받아 저장한다(done/failed 모두).
 *   4) draft(JobDonePayload) 파싱 → status=failed 면 502, done 이면 stops 로 접는다.
 *   5) hub /v1/weather 를 별도 호출해 weather_forecast 를 채운다(best-effort).
 *   6) 동기 200 TripGenerateResponse 반환.
 *
 * 기존 자산 재사용: AgentClient · DraftStore · RecommendRequest/RecommendResponse DTO.
 * (SoT D6/B7 의 long-poll 대신 사용자 결정 D-1 의 동기 facade — 위험 등록부 등재 대상.)
 */
@Service
public class TripService {

    private static final Logger log = LoggerFactory.getLogger(TripService.class);

    private final RecommendService recommendService;
    private final DraftStore draftStore;
    private final HubWeatherClient hubWeatherClient;
    private final TripStopsAssembler stopsAssembler;
    private final ReviewSummaryService reviewSummaryService;
    private final ObjectMapper objectMapper;
    private final long pollTimeoutSeconds;
    private final long pollIntervalMs;
    private final ScheduleService scheduleService;

    public TripService(
            RecommendService recommendService,
            DraftStore draftStore,
            HubWeatherClient hubWeatherClient,
            TripStopsAssembler stopsAssembler,
            ReviewSummaryService reviewSummaryService,
            ObjectMapper objectMapper,
            ScheduleService scheduleService,
            @Value("${trip.poll-timeout-seconds:150}") long pollTimeoutSeconds,
            @Value("${trip.poll-interval-ms:700}") long pollIntervalMs
    ) {
        this.recommendService = recommendService;
        this.draftStore = draftStore;
        this.hubWeatherClient = hubWeatherClient;
        this.stopsAssembler = stopsAssembler;
        this.reviewSummaryService = reviewSummaryService;
        this.objectMapper = objectMapper;
        this.scheduleService = scheduleService;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * 방문지 목록으로 요약 선작성을 예약한다.
     *
     * 제출만 하고 즉시 돌아가므로 응답이 늦어지지 않는다. 이름이 없는
     * 항목은 캐시 키를 만들 수 없어 건너뛴다.
     */
    private void prewarmSummaries(List<TripStop> stops) {
        List<ReviewSummaryService.PrewarmPlace> places = stops.stream()
                .filter(s -> s.name() != null && !s.name().isBlank())
                .map(s -> new ReviewSummaryService.PrewarmPlace(
                        s.name(), s.category()))
                .toList();
        reviewSummaryService.prewarm(places);
    }

    /**
     * trip 생성 동기 처리. 성공 시 완성된 TripGenerateResponse, 실패 시 예외를 던진다
     * (TripGenerationException→502, TripTimeoutException→504, IllegalArgument→400).
     *
     * request: 검증 완료된 TripGenerateRequest.
     */
    public TripGenerateResponse generate(TripGenerateRequest request) {
        return generate(request, null);
    }

    /**
     * 위와 같되, 로그인한 사용자면 저장해 둔 취향을 추천에 얹는다.
     *
     * userId: 유효한 토큰이 있을 때만 채워진다. null 이면 기존 경로와
     *         완전히 같게 동작한다.
     */
    public TripGenerateResponse generate(
            TripGenerateRequest request, Long userId) {
        // 0) 시/도 명칭 정규화 (잠재오류①: client 구 명칭 → region_grid 개편 명칭).
        //    agent 위임과 hub 날씨 호출 양쪽에 동일한 정규화 값을 사용한다.
        String province = TripMapping.normalizeProvince(request.location().province());
        String city = request.location().city();

        // 1) 요청 변환 → RecommendService 경유 위임
        //    agentClient 를 직접 부르지 않는다. RecommendService 를 거쳐야
        //    재사용 캐시 조회·연결고리 등록·recommend_jobs in_progress 기록이
        //    함께 이뤄진다. 직접 호출하던 시절에는 client 유일 경로인 이 흐름이
        //    캐시를 전혀 쓰지 못했고, 완료 이벤트가 오기 전까지 admin 콘솔에
        //    진행 중 job 이 보이지 않았다.
        RecommendRequest recommendRequest = toRecommendRequest(request, province, city);
        RecommendService.RecommendationResult delegated =
                recommendService.createRecommendationDetailed(
                        recommendRequest, userId);
        JobAccepted accepted = delegated.accepted();
        String jobId = accepted.jobId();
        log.info("trip generate started job_id={} cacheHit={}", jobId, delegated.cacheHit());

        // 2) draft 폴링(동기 대기)
        String draftJson = awaitDraft(jobId);

        // 3) draft 파싱 → 검증
        RecommendResponse result = parseDraft(jobId, draftJson);
        if ("failed".equalsIgnoreCase(result.status())) {
            String reason = result.error() != null ? result.error() : "recommendation failed";
            throw new TripGenerationException(reason);
        }

        // 4) stops 변환 (경로 성공 구간은 이동 시간/거리를 실측으로 대체)
        //    저장된 일정 상세 조회도 같은 조립기를 쓴다 — 두 화면이 같은
        //    방문 시각·이동 카드·폴리라인을 보여야 한다.
        Schedule schedule = request.schedule();
        List<TripStop> stops = stopsAssembler.assemble(
                result,
                request.transport(),
                schedule.activeStartHour(),
                schedule.activeEndHour());
        int totalDuration = TripStopsAssembler.totalDurationMinutes(stops);

        // 5) 장소 요약을 미리 만들어 둔다. 일정에 담긴 장소는 대부분 한 번씩
        //    눌러 보는데, 누를 때 만들면 그 자리에서 모델 응답을 기다려야 한다.
        //    제출만 하고 넘어가므로 이 응답이 늦어지지 않는다.
        prewarmSummaries(stops);

        // 6) 날씨(best-effort)
        HubWeatherResponse weather = hubWeatherClient.fetchWeather(
                province,
                city,
                schedule.startDate(),
                schedule.endDate());
        List<WeatherForecastItem> forecast = TripMapping.toWeatherForecast(weather);

        log.info("trip generate done job_id={} stops={} weatherDays={}",
                jobId, stops.size(), forecast.size());
        return new TripGenerateResponse(
                jobId, totalDuration, stops, forecast,
                result.warnings(), result.timelineStatus());
    }

    /**
     * 저장된 일정을 지금 날씨로 다시 짜서 완성된 결과를 돌려준다(동기).
     *
     * client 는 job 폴링 경로를 구현하지 않는다 — 추천은 generate/route 처럼
     * 한 번의 요청으로 완성된 일정을 받는 계약뿐이다. 날씨 재추천만 비동기로
     * 두면 앱이 폴링 코드를 새로 만들어야 하고, 추천 흐름이 두 갈래로 갈린다.
     * 그래서 같은 동기 형태로 맞춘다 — 결과 화면 코드도 그대로 재사용된다.
     *
     * 조건은 저장된 일정에서 그대로 가져온다(지역·기간·이동수단·활동 시간대).
     * 사용자가 조건을 다시 입력하지 않아도 되는 것이 이 기능의 요점이다.
     * 검증(소유자·지역 보유·지나지 않은 일정)은 일정 도메인이 끝낸 뒤 사양만
     * 넘겨받는다.
     *
     * 재사용 캐시는 타지 않는다(createFreshRecommendation). 캐시 키에 날씨가
     * 없어 조건이 그대로인 이 요청은 반드시 캐시에 맞고, 맞으면 agent 가 돌지
     * 않아 바뀐 날씨가 반영될 기회 자체가 사라진다.
     *
     * 기준선 갱신은 <b>성공한 뒤에만</b> 한다. 실패한 재추천으로 알림을 지우면
     * 사용자는 바뀐 날씨를 모른 채 옛 코스를 그대로 들고 간다.
     *
     * scheduleId: 다시 짤 일정. userId: 소유자(다르면 404).
     */
    public TripGenerateResponse replan(Long scheduleId, Long userId) {
        ScheduleReplanSpec spec = scheduleService.replanSpec(scheduleId, userId);

        int startHour = spec.activeStartHour() != null
                ? spec.activeStartHour()
                : TripStopsAssembler.DEFAULT_START_HOUR;
        int endHour = spec.activeEndHour() != null
                ? spec.activeEndHour()
                : TripStopsAssembler.DEFAULT_END_HOUR;

        RecommendRequest recommendRequest = new RecommendRequest(
                new DateRange(
                        spec.dateStart(),
                        spec.dateEnd(),
                        TripMapping.hourToLocalTime(startHour),
                        TripMapping.hourToLocalTime(endHour)),
                null,
                null,
                TripMapping.toAgentMobility(spec.transport()),
                spec.province(),
                spec.city(),
                String.valueOf(spec.scheduleId()),
                null,
                null,
                null);

        String jobId = recommendService
                .createFreshRecommendation(recommendRequest).jobId();
        log.info("trip replan started job_id={} schedule_id={}", jobId, scheduleId);

        RecommendResponse result = parseDraft(jobId, awaitDraft(jobId));
        if ("failed".equalsIgnoreCase(result.status())) {
            String reason = result.error() != null ? result.error() : "recommendation failed";
            throw new TripGenerationException(reason);
        }

        List<TripStop> stops = stopsAssembler.assemble(
                result, spec.transport(), startHour, endHour);
        prewarmSummaries(stops);

        List<WeatherForecastItem> forecast = TripMapping.toWeatherForecast(
                hubWeatherClient.fetchWeather(
                        spec.province(), spec.city(),
                        spec.dateStart(), spec.dateEnd()));

        // 여기까지 왔으면 사용자는 지금 날씨를 반영한 결과를 받은 것이다.
        scheduleService.markReplanned(scheduleId, userId);

        log.info("trip replan done job_id={} stops={}", jobId, stops.size());
        return new TripGenerateResponse(
                jobId,
                TripStopsAssembler.totalDurationMinutes(stops),
                stops,
                forecast,
                result.warnings(),
                result.timelineStatus());
    }

    /**
     * 사용자가 고른 장소들의 동선을 동기로 만들어 돌려준다.
     *
     * generate 와 같은 흐름이되 장소 탐색·선정을 건너뛴다. 결과 형태도 같아서
     * client 는 생성 직후 화면과 같은 코드로 렌더링한다 — 방문 시각, 이동 카드,
     * 도로 폴리라인까지 여기서 조립해 넘긴다.
     *
     * 예산·테마를 받지 않는다. 후보를 고르는 데 쓰는 조건인데 장소가 이미
     * 정해진 요청이라 쓸 곳이 없다.
     *
     * request: 검증 완료된 TripRouteRequest.
     */
    public TripGenerateResponse route(TripRouteRequest request) {
        String province = TripMapping.normalizeProvince(request.location().province());
        String city = request.location().city();
        Schedule schedule = request.schedule();

        DateRange date = new DateRange(
                schedule.startDate(),
                schedule.endDate(),
                TripMapping.hourToLocalTime(schedule.activeStartHour()),
                TripMapping.hourToLocalTime(schedule.activeEndHour()));
        // stage 는 서비스가 강제하므로 여기서는 비워 보낸다.
        RecommendRequest recommendRequest = new RecommendRequest(
                date,
                null,
                null,
                TripMapping.toAgentMobility(request.transport()),
                province,
                city,
                null,
                null,
                null,
                request.places());

        JobAccepted accepted = recommendService.createRouteJob(recommendRequest);
        String jobId = accepted.jobId();
        log.info("trip route started job_id={} places={}",
                jobId, request.places().size());

        RecommendResponse result = parseDraft(jobId, awaitDraft(jobId));
        if ("failed".equalsIgnoreCase(result.status())) {
            String reason = result.error() != null ? result.error() : "recommendation failed";
            throw new TripGenerationException(reason);
        }

        List<TripStop> stops = stopsAssembler.assemble(
                result,
                request.transport(),
                schedule.activeStartHour(),
                schedule.activeEndHour());
        int totalDuration = TripStopsAssembler.totalDurationMinutes(stops);

        prewarmSummaries(stops);

        HubWeatherResponse weather = hubWeatherClient.fetchWeather(
                province, city, schedule.startDate(), schedule.endDate());
        List<WeatherForecastItem> forecast = TripMapping.toWeatherForecast(weather);

        log.info("trip route done job_id={} stops={}", jobId, stops.size());
        return new TripGenerateResponse(
                jobId, totalDuration, stops, forecast,
                result.warnings(), result.timelineStatus());
    }

    /**
     * 같은 조건에서 다른 장소로 다시 추천해 동기로 돌려준다.
     *
     * generate 와 흐름은 같고 두 가지가 다르다. 재사용 캐시를 타지 않으며
     * (같은 조건이면 같은 결과를 돌려주는 캐시라, 그대로 두면 "다른 장소"라는
     * 요구가 조용히 무시된다), 이전 추천의 장소를 제외 목록으로 실어 보낸다.
     *
     * keep 이 있으면 그 장소들은 그대로 두고 나머지 자리만 새로 채운다.
     *
     * 하루 재탐색 한도를 소비한다 — 초과하면 409 로 끝나며 agent 를 부르지
     * 않는다.
     *
     * request: 검증 완료된 TripResearchRequest.
     */
    public TripGenerateResponse research(TripResearchRequest request) {
        String province = TripMapping.normalizeProvince(request.location().province());
        String city = request.location().city();
        Schedule schedule = request.schedule();

        DateRange date = new DateRange(
                schedule.startDate(),
                schedule.endDate(),
                TripMapping.hourToLocalTime(schedule.activeStartHour()),
                TripMapping.hourToLocalTime(schedule.activeEndHour()));
        // stage/exclude/places 는 RecommendService.research 가 채운다.
        RecommendRequest recommendRequest = new RecommendRequest(
                date,
                TripMapping.toAgentBudget(request.budget()),
                request.themes(),
                TripMapping.toAgentMobility(request.transport()),
                province,
                city,
                request.scheduleId() == null
                        ? null : String.valueOf(request.scheduleId()),
                null,
                null,
                null);

        JobAccepted accepted = recommendService.research(
                request.prevTripId(), recommendRequest,
                request.exclude(), request.keep());
        String jobId = accepted.jobId();
        log.info("trip research started job_id={} prev={} keep={}",
                jobId, request.prevTripId(),
                request.keep() == null ? 0 : request.keep().size());

        RecommendResponse result = parseDraft(jobId, awaitDraft(jobId));
        if ("failed".equalsIgnoreCase(result.status())) {
            String reason = result.error() != null ? result.error() : "recommendation failed";
            throw new TripGenerationException(reason);
        }

        List<TripStop> stops = stopsAssembler.assemble(
                result,
                request.transport(),
                schedule.activeStartHour(),
                schedule.activeEndHour());
        int totalDuration = TripStopsAssembler.totalDurationMinutes(stops);

        prewarmSummaries(stops);

        HubWeatherResponse weather = hubWeatherClient.fetchWeather(
                province, city, schedule.startDate(), schedule.endDate());
        List<WeatherForecastItem> forecast = TripMapping.toWeatherForecast(weather);

        log.info("trip research done job_id={} stops={}", jobId, stops.size());
        return new TripGenerateResponse(
                jobId, totalDuration, stops, forecast,
                result.warnings(), result.timelineStatus());
    }

    /** client 요청 → agent RecommendRequest (TripMapping 규칙 적용; province/city 는 정규화된 값). */
    private static RecommendRequest toRecommendRequest(
            TripGenerateRequest req, String province, String city) {
        Schedule s = req.schedule();
        DateRange date = new DateRange(
                s.startDate(),
                s.endDate(),
                TripMapping.hourToLocalTime(s.activeStartHour()),
                TripMapping.hourToLocalTime(s.activeEndHour()));
        // 동기 facade 는 항상 초기 추천이다 — stage="init", exclude 없음.
        // places 도 비운다: 장소를 골라 오는 흐름은 별도 엔드포인트가 받는다.
        return new RecommendRequest(
                date,
                TripMapping.toAgentBudget(req.budget()),
                req.themes(),
                TripMapping.toAgentMobility(req.transport()),
                province,
                city,
                null,
                "init",
                null,
                null);
    }

    /** draft 가 나타날 때까지 폴링. 한도 초과 시 TripTimeoutException. */
    private String awaitDraft(String jobId) {
        long deadlineNanos = System.nanoTime()
                + Duration.ofSeconds(pollTimeoutSeconds).toNanos();
        while (true) {
            Optional<String> draft = draftStore.find(jobId);
            if (draft.isPresent()) {
                return draft.get();
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new TripTimeoutException(jobId);
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TripTimeoutException(jobId);
            }
        }
    }

    /** draft JSON → RecommendResponse. 파싱 실패는 비정상 결과로 간주(502). */
    private RecommendResponse parseDraft(String jobId, String draftJson) {
        try {
            return objectMapper.readValue(draftJson, RecommendResponse.class);
        } catch (JsonProcessingException e) {
            log.error("draft parse failed job_id={} reason={}", jobId, e.getMessage());
            throw new TripGenerationException("recommendation result is malformed");
        }
    }

}
