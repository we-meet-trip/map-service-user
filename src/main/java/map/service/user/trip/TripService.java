package map.service.user.trip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import map.service.user.recommend.AgentClient;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Place;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.trip.dto.HubWeatherResponse;
import map.service.user.trip.dto.Schedule;
import map.service.user.trip.dto.TransportToNext;
import map.service.user.trip.dto.TripGenerateRequest;
import map.service.user.trip.dto.TripGenerateResponse;
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

    private final AgentClient agentClient;
    private final DraftStore draftStore;
    private final HubWeatherClient hubWeatherClient;
    private final ObjectMapper objectMapper;
    private final long pollTimeoutSeconds;
    private final long pollIntervalMs;

    public TripService(
            AgentClient agentClient,
            DraftStore draftStore,
            HubWeatherClient hubWeatherClient,
            ObjectMapper objectMapper,
            @Value("${trip.poll-timeout-seconds:120}") long pollTimeoutSeconds,
            @Value("${trip.poll-interval-ms:700}") long pollIntervalMs
    ) {
        this.agentClient = agentClient;
        this.draftStore = draftStore;
        this.hubWeatherClient = hubWeatherClient;
        this.objectMapper = objectMapper;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * trip 생성 동기 처리. 성공 시 완성된 TripGenerateResponse, 실패 시 예외를 던진다
     * (TripGenerationException→502, TripTimeoutException→504, IllegalArgument→400).
     *
     * request: 검증 완료된 TripGenerateRequest.
     */
    public TripGenerateResponse generate(TripGenerateRequest request) {
        // 0) 시/도 명칭 정규화 (잠재오류①: client 구 명칭 → region_grid 개편 명칭).
        //    agent 위임과 hub 날씨 호출 양쪽에 동일한 정규화 값을 사용한다.
        String province = TripMapping.normalizeProvince(request.location().province());
        String city = request.location().city();

        // 1) 요청 변환 → agent 위임
        RecommendRequest recommendRequest = toRecommendRequest(request, province, city);
        JobAccepted accepted = agentClient.requestRecommend(recommendRequest);
        String jobId = accepted.jobId();
        log.info("trip generate started job_id={}", jobId);

        // 2) draft 폴링(동기 대기)
        String draftJson = awaitDraft(jobId);

        // 3) draft 파싱 → 검증
        RecommendResponse result = parseDraft(jobId, draftJson);
        if ("failed".equalsIgnoreCase(result.status())) {
            String reason = result.error() != null ? result.error() : "recommendation failed";
            throw new TripGenerationException(reason);
        }

        // 4) stops 변환
        Schedule schedule = request.schedule();
        List<TripStop> stops = toStops(request, result, schedule);
        int totalDuration = TripMapping.totalDurationMinutes(result.legs());

        // 5) 날씨(best-effort)
        HubWeatherResponse weather = hubWeatherClient.fetchWeather(
                province,
                city,
                schedule.startDate(),
                schedule.endDate());
        List<WeatherForecastItem> forecast = TripMapping.toWeatherForecast(weather);

        log.info("trip generate done job_id={} stops={} weatherDays={}",
                jobId, stops.size(), forecast.size());
        return new TripGenerateResponse(jobId, totalDuration, stops, forecast);
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
        return new RecommendRequest(
                date,
                TripMapping.toAgentBudget(req.budget()),
                req.themes(),
                TripMapping.toAgentMobility(req.transport()),
                province,
                city);
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

    /** places + visit_order + legs → client stops[]. */
    private static List<TripStop> toStops(
            TripGenerateRequest req, RecommendResponse result, Schedule schedule
    ) {
        List<Place> places = result.places();
        List<Integer> order = result.visitOrder();
        List<Leg> legs = result.legs();
        if (places == null || places.isEmpty() || order == null || order.isEmpty()) {
            throw new TripGenerationException("recommendation has no places");
        }
        Map<Integer, Place> byId = new HashMap<>();
        for (Place p : places) {
            byId.put(p.placeId(), p);
        }
        int n = order.size();
        int startHour = schedule.activeStartHour();
        int endHour = schedule.activeEndHour();
        String transport = req.transport();
        String label = TripMapping.transportLabel(transport);

        List<TripStop> stops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Place p = byId.get(order.get(i));
            if (p == null) {
                throw new TripGenerationException(
                        "place_id " + order.get(i) + " missing in places");
            }
            TransportToNext toNext = null;
            if (i < n - 1 && legs != null && i < legs.size()) {
                Leg leg = legs.get(i);
                toNext = new TransportToNext(
                        transport, label,
                        leg.estimatedDurationMin(),
                        leg.estimatedDistanceKm());
            }
            stops.add(new TripStop(
                    i + 1,
                    p.name(),
                    p.address(),
                    TripMapping.stopTime(startHour, endHour, i, n),
                    p.lat(),
                    p.lng(),
                    toNext));
        }
        return stops;
    }
}
