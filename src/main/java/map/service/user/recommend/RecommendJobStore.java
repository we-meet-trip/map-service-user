package map.service.user.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private final RecommendTrainingRepository trainingRepository;
    private final RecommendEditRepository editRepository;
    private final ObjectMapper objectMapper;

    public RecommendJobStore(RecommendJobRepository repository,
                             RecommendTrainingRepository trainingRepository,
                             RecommendEditRepository editRepository,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.trainingRepository = trainingRepository;
        this.editRepository = editRepository;
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
        insertInProgress(jobId, scheduleId, null);
    }

    /**
     * 접수 기록에 출처(어느 경로가 만든 잡인지)를 함께 남긴다.
     *
     * 한 문장(upsert)으로 처리한다. 예전에는 "있으면 넘어가기" 였는데, 그러면
     * 완료 이벤트가 접수보다 먼저 도착한 잡은 mode·source 가 영영 비어 있게
     * 된다. 그렇다고 통째로 덮으면 끝난 잡이 진행 중으로 되돌아간다. 그래서
     * status 는 건드리지 않고 비어 있는 칸만 채운다.
     *
     * origin 이 null 이면 출처 없이 접수만 기록한다(옛 호출부 호환).
     */
    @Transactional
    public void insertInProgress(String jobId, String scheduleId, JobOrigin origin) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return;
        }
        try {
            if (!repository.existsById(uuid)) {
                try {
                    repository.save(new RecommendJobEntity(
                            uuid, scheduleId, "in_progress", null, null, null));
                } catch (DataIntegrityViolationException e) {
                    // 있는지 보고 넣는 사이에 완료 처리가 같은 행을 만들었다.
                    // 그쪽이 더 나중 상태이므로 넣지 않고, 아래에서 빈 칸만 채운다.
                    log.debug("recommend job insert lost race job_id={}", jobId);
                }
            }
            // 넣었든 이미 있었든 여기는 반드시 지난다. 완료가 먼저 도착해 만든
            // 행에도 출처가 남아야 하기 때문이다 — 예전에는 이 경우 mode·source
            // 가 영영 비어 있었다.
            if (origin != null) {
                repository.fillOriginIfAbsent(
                        uuid, scheduleId, origin.mode(),
                        parseUuid(origin.parentJobId()), origin.source(),
                        origin.ownerUserId());
                // 성향은 위 문장에 얹지 않는다. 그쪽은 네이티브 SQL 이고 JSON
                // 캐스팅 구문이 DB 마다 달라, 시험이 쓰는 DB 에서 통째로 깨진다.
                // 대신 JPA 가 이미 아는 길로 따로 채운다.
                fillSegmentIfAbsent(uuid, origin.userSegment());
            }
        } catch (RuntimeException e) {
            log.warn("recommend job insert failed job_id={} reason={}", jobId, e.getMessage());
        }
    }

    /**
     * 성향 스냅샷을 비어 있을 때만 채운다.
     *
     * 여기서 실패해도 접수는 이미 끝나 있다. 성향은 학습에 쓰는 곁가지라,
     * 못 남겼다고 요청을 깨뜨리지 않는다.
     */
    private void fillSegmentIfAbsent(UUID uuid, Map<String, Object> segment) {
        if (segment == null) {
            return;
        }
        try {
            JsonNode node = objectMapper.valueToTree(segment);
            repository.findById(uuid).ifPresent(entity -> {
                entity.fillUserSegmentIfAbsent(node);
                repository.save(entity);
            });
        } catch (RuntimeException e) {
            log.warn("user segment record failed job_id={} reason={}",
                    uuid, e.getMessage());
        }
    }

    /**
     * 잡의 출처. 어느 경로가 만들었고, 재탐색이면 무엇을 거부한 것인지.
     *
     * mode: init | research | route | refresh
     * parentJobId: 이 잡이 딛고 선 앞의 잡.
     *              재탐색이면 버린 원본이고("이 결과를 버리고 저것을 받았다"),
     *              캐시로 답했거나 앞선 요청에 얹혀 간 잡이면 그 결과를 처음
     *              만든 잡이다. 뒤엣것이 없으면 사용자가 실제로 저장한 일정이
     *              어떤 후보에서 나왔는지 되짚을 수 없다 — 후보와 선택 근거는
     *              원본 잡에만 붙어 있기 때문이다.
     *              어느 쪽인지는 mode·source 로 갈린다.
     * source: agent | cache_hit. 캐시로 답한 잡은 agent 가 돌지 않았는데도
     *         완료로 기록되므로, 세는 쪽이 갈라 볼 수 있어야 한다.
     */
    public record JobOrigin(String mode, String parentJobId, String source,
                            Long ownerUserId, Map<String, Object> userSegment) {

        public static JobOrigin agent(String mode) {
            return new JobOrigin(mode, null, "agent", null, null);
        }

        public static JobOrigin research(String parentJobId) {
            return new JobOrigin("research", parentJobId, "agent", null, null);
        }

        public static JobOrigin cacheHit() {
            return cacheHit(null);
        }

        /** 캐시로 답한 잡. 그 결과를 처음 만든 잡을 함께 가리킨다. */
        public static JobOrigin cacheHit(String originJobId) {
            return new JobOrigin("init", originJobId, "cache_hit", null, null);
        }

        /**
         * 앞선 요청에 얹혀 간 잡에 원본을 뒤늦게 달아 준다.
         *
         * 접수할 때는 캐시로 답하게 될지 몰라 agent 로 기록해 두고, 결과가
         * 나온 뒤에야 어느 잡이 만든 것인지 알게 된다. 그때 계보만 채운다 —
         * source 를 고쳐 쓰지 않는 이유는 이 잡이 실제로 agent 를 부르려던
         * 잡이었고, 부르지 않았다는 사실은 원본이 따로 있다는 것으로 이미
         * 드러나기 때문이다.
         */
        public static JobOrigin joined(String originJobId) {
            return new JobOrigin("init", originJobId, "agent", null, null);
        }

        /** 소유자를 덧붙인다. 토큰이 없어 모르면 그대로 둔다. */
        public JobOrigin ownedBy(Long userId) {
            return userId == null ? this
                    : new JobOrigin(mode, parentJobId, source, userId, userSegment);
        }

        /**
         * 물어본 시점의 성향을 덧붙인다. 모르면 그대로 둔다.
         *
         * 빈 것도 모르는 것으로 본다. 칸이 하나도 없는 성향을 남기면 읽는
         * 쪽에서 "성향이 있는 잡" 으로 세는데 실제로는 아무 것도 없어,
         * 성향별로 갈라 볼 때 빈 묶음이 하나 더 생긴다.
         */
        public JobOrigin withSegment(Map<String, Object> segment) {
            return segment == null || segment.isEmpty() ? this
                    : new JobOrigin(mode, parentJobId, source, ownerUserId, segment);
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

    /**
     * 완료 상태와 학습 신호를 한 트랜잭션으로 기록한다. **실패하면 예외를 던진다.**
     *
     * markFinished 와 갈라 둔 이유가 이것 하나다. markFinished 는 어떤 예외도
     * 삼키는데, stream 소비 경로가 그것을 쓰면 기록이 실패해도 그대로 ack 되어
     * 메시지가 사라진다. 사용자는 Redis 초안으로 결과를 이미 받았으므로 아무도
     * 눈치채지 못한 채 학습 신호만 조용히 없어진다.
     *
     * 여기서 던지면 소비자가 ack 하지 않고, 메시지는 PEL 에 남아 회수 대상이
     * 된다. 재시도가 다 떨어지면 DLQ 로 가므로 본문은 어느 쪽이든 보존된다.
     *
     * 신호 저장은 있으면 건너뛴다 — 재처리로 두 번 들어와도 처음 것이 남는다.
     * 신호가 없거나 형태가 아니면 상태만 기록한다. 신호 때문에 완료 기록 자체를
     * 막지는 않는다.
     */
    @Transactional
    public void recordCompletion(String jobId, String status,
                                 String payloadJson, String trainingJson) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return;
        }
        JsonNode payload = parsePayload(payloadJson);
        String normalized = normalizeStatus(status);
        try {
            writeFinished(uuid, normalized, payload);
        } catch (DataIntegrityViolationException e) {
            // 행이 없다고 보고 새로 넣는 사이에 접수 기록이 같은 행을 만들었다.
            writeFinished(uuid, normalized, payload);
        }
        writeTraining(uuid, trainingJson);
    }

    /**
     * 잡의 소유자를 돌려준다. 모르면 null — 그때는 막지 않는다.
     *
     * 토큰 없이 만든 잡과 이 기능 이전 잡은 소유자가 없다. 모른다는 이유로
     * 거절하면 인증을 켜기도 전에 기존 사용자가 잠긴다.
     */
    public Long ownerOf(String jobId) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return null;
        }
        try {
            return repository.findById(uuid)
                    .map(RecommendJobEntity::getOwnerUserId)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("recommend job owner lookup failed job_id={} reason={}",
                    jobId, e.getMessage());
            return null;
        }
    }

    /**
     * 초안 수정 전후를 남기고, 완료 결과도 고친 값으로 맞춘다.
     *
     * 결과를 함께 고치는 이유: 예전에는 Redis 초안만 바꿔서, 초안이 만료된 뒤
     * PG 로 물으면 고치기 이전 결과가 되살아났다. 사용자가 뺀 장소가 다시
     * 나타나는 셈이다.
     *
     * 같은 요청이 재시도로 두 번 오면 두 번째는 아무 것도 하지 않는다.
     * 순번이 부딪히면 한 번 다시 잡아 본다 — 동시에 두 수정이 들어온 경우다.
     *
     * @return 기록했으면 true, 재시도로 판정해 건너뛰었으면 false.
     */
    @Transactional
    public boolean recordEdit(String jobId, Long actorUserId, String idempotencyKey,
                              String beforeJson, String afterJson) {
        UUID uuid = parseUuid(jobId);
        if (uuid == null) {
            return false;
        }
        if (idempotencyKey != null
                && editRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.debug("edit already recorded job_id={} key={}", jobId, idempotencyKey);
            return false;
        }
        JsonNode before = parsePayload(beforeJson);
        JsonNode after = parsePayload(afterJson);
        try {
            saveEdit(uuid, actorUserId, idempotencyKey, before, after);
        } catch (DataIntegrityViolationException e) {
            // 순번이 부딪혔다. 다시 읽어 한 번 더 시도한다. 두 번째도 부딪히면
            // 그대로 올려 보낸다 — 조용히 삼키면 수정 하나가 기록 없이 사라진다.
            saveEdit(uuid, actorUserId, idempotencyKey, before, after);
        }
        writeFinished(uuid, "done", after);
        return true;
    }

    private void saveEdit(UUID uuid, Long actorUserId, String idempotencyKey,
                          JsonNode before, JsonNode after) {
        editRepository.saveAndFlush(new RecommendEditEntity(
                uuid, editRepository.nextSeq(uuid), actorUserId,
                idempotencyKey, before, after));
    }

    /** 학습 신호를 보관한다. 이미 있으면 두고, 형태가 아니면 건너뛴다. */
    private void writeTraining(UUID uuid, String trainingJson) {
        if (trainingJson == null || trainingJson.isBlank()) {
            return;
        }
        JsonNode signal;
        Integer version;
        try {
            signal = objectMapper.readTree(trainingJson);
            JsonNode v = signal.get("schema_version");
            version = v != null && v.isInt() ? v.intValue() : null;
        } catch (JsonProcessingException e) {
            log.warn("training signal unreadable job_id={} reason={}", uuid, e.getMessage());
            return;
        }
        // 계약이 요구하는 두 칸이 없으면 DB CHECK 에 걸려 완료 기록까지 함께
        // 되돌아간다. 신호 하나 때문에 상태 기록을 잃지 않도록 여기서 거른다.
        if (version == null || !signal.has("path")) {
            log.warn("training signal shape invalid job_id={}", uuid);
            return;
        }
        if (trainingRepository.existsById(uuid)) {
            return;
        }
        try {
            trainingRepository.save(new RecommendTrainingEntity(uuid, version, signal));
        } catch (DataIntegrityViolationException e) {
            // 같은 잡을 두 소비자가 동시에 처리했다. 먼저 넣은 쪽 것을 쓴다.
            log.debug("training signal already stored job_id={}", uuid);
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
