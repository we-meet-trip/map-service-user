package map.service.user.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * RecommendJobStore — 추천 작업 상태/결과의 PostgreSQL write-through 저장소
 *
 * Redis draft(1차·휘발성) 옆에서 추천 작업의 상태/완료 결과를 recommend_jobs
 * 테이블에 함께 기록하고(write-through), Redis 미스 시 완료 결과를 폴백 조회한다.
 *
 * 모든 메서드는 best-effort 이다 — 어떤 예외도 밖으로 던지지 않고 경고 로그만
 * 남긴다. PG 장애가 추천 생성/완료 처리(및 stream ack)를 막아서는 안 되며,
 * 폴백 조회 실패는 곧 "결과 없음"으로만 취급되어 long-poll 흐름을 바꾸지 않는다.
 *
 * repository: RecommendJobRepository. recommend_jobs CRUD.
 * objectMapper: payload JSON ↔ JsonNode 변환.
 */
@Component
public class RecommendJobStore {

    private static final Logger log = LoggerFactory.getLogger(RecommendJobStore.class);

    /** status 컬럼 CHECK 제약이 허용하는 완료 상태. */
    private static final Set<String> TERMINAL_STATUS = Set.of("done", "failed");

    private final RecommendJobRepository repository;
    private final ObjectMapper objectMapper;

    public RecommendJobStore(RecommendJobRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 추천 작업을 in_progress 상태로 최초 기록한다(write-through).
     *
     * jobId 가 유효한 UUID 가 아니면 무시한다. 같은 job_id 가 이미 있으면 그대로 둔다
     * — 완료 처리가 먼저 도착했을 수 있고, 그것을 진행 중으로 되돌리면 안 된다.
     * 어떤 예외도 던지지 않는다.
     *
     * jobId: agent 발급 작업 UUID 문자열.
     * scheduleId: 연관 일정 식별자(nullable).
     */
    public void insertInProgress(String jobId, String scheduleId) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return;
        }
        try {
            if (repository.existsById(uuid)) {
                return;
            }
            repository.save(new RecommendJobEntity(
                    uuid, scheduleId, "in_progress", null, null, null));
        } catch (DataIntegrityViolationException e) {
            // 있는지 보고 넣는 사이에 완료 처리가 같은 행을 만들었다. 그쪽이 더
            // 나중 상태이므로 이쪽은 물러난다.
            log.debug("recommend job insert lost race job_id={}", jobId);
        } catch (RuntimeException e) {
            log.warn("recommend job insert failed job_id={} reason={}", jobId, e.getMessage());
        }
    }

    /**
     * 추천 작업을 완료(done/failed)로 갱신하고 결과 payload 를 기록한다.
     *
     * 기존 행이 있으면 상태/결과/완료시각만 갱신(schedule_id 등 보존)하고, 없으면
     * 새 행을 만든다. status 는 CHECK 도메인(done/failed)으로 정규화한다. payload
     * 파싱 실패 시 결과는 비운 채 상태만 기록한다. 어떤 예외도 던지지 않는다.
     *
     * jobId: 작업 UUID 문자열.
     * status: stream 이 전달한 완료 상태(null/미상은 done 으로 정규화).
     * payloadJson: 완료 결과 JSON 문자열.
     */
    public void markFinished(String jobId, String status, String payloadJson) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return;
        }
        try {
            JsonNode payload = parsePayload(payloadJson);
            String normalized = normalizeStatus(status);
            try {
                writeFinished(uuid, normalized, payload);
            } catch (DataIntegrityViolationException e) {
                // 행이 없다고 보고 새로 넣는 사이에 최초 기록이 같은 행을 만들었다.
                // 이제는 있으므로 갱신 경로로 한 번 더 간다. 이 한 번을 포기하면
                // 실제로 끝난 작업이 진행 중인 채로 영영 남는다.
                writeFinished(uuid, normalized, payload);
            }
        } catch (RuntimeException e) {
            log.warn("recommend job finish failed job_id={} reason={}", jobId, e.getMessage());
        }
    }

    /** 완료 상태를 기록한다. 행이 있으면 갱신, 없으면 생성. */
    private void writeFinished(UUID uuid, String status, JsonNode payload) {
        RecommendJobEntity entity = repository.findById(uuid).orElse(null);
        if (entity == null) {
            entity = new RecommendJobEntity(
                    uuid, null, status, payload, null, OffsetDateTime.now());
        } else {
            entity.setStatus(status);
            entity.setResultPayload(payload);
            entity.setFinishedAt(OffsetDateTime.now());
        }
        repository.save(entity);
    }

    /**
     * 완료(done/failed)된 작업의 결과 payload 문자열을 조회한다(폴백 읽기).
     *
     * jobId 가 유효 UUID 가 아니거나, 행이 없거나, 완료 상태가 아니거나, 결과가
     * 비어 있으면 Optional.empty. 어떤 예외도 던지지 않으며 실패 시 empty 를 반환한다.
     *
     * jobId: 작업 UUID 문자열.
     */
    public Optional<String> findFinishedPayload(String jobId) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return Optional.empty();
        }
        try {
            RecommendJobEntity entity = repository.findById(uuid).orElse(null);
            if (entity == null
                    || !TERMINAL_STATUS.contains(entity.getStatus())
                    || entity.getResultPayload() == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.writeValueAsString(entity.getResultPayload()));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("recommend job fallback read failed job_id={} reason={}",
                    jobId, e.getMessage());
            return Optional.empty();
        }
    }

    /** jobId 를 UUID 로 파싱. 형식 오류/ null 은 null 반환(no-op 유도). */
    private static UUID parseUuid(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** payload JSON → JsonNode. 파싱 실패 시 null(결과 없이 상태만 기록). */
    private JsonNode parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** 완료 상태를 CHECK 도메인으로 정규화: failed 는 그대로, 그 외는 done. */
    private static String normalizeStatus(String status) {
        return "failed".equalsIgnoreCase(status) ? "failed" : "done";
    }
}
