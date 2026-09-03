package map.service.user.admin;

import map.service.user.admin.dto.DlqActionResult;
import map.service.user.admin.dto.DlqEntry;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AdminDlqService — 운영 콘솔 위임 DLQ 조회/재처리/폐기
 *
 * map-service-admin 이 /internal/admin/dlq* 로 호출한다. DLQ 스트림
 * (agent:jobs:done:dlq)을 XREVRANGE 로 조회하고, 재처리는 원 소비 로직
 * (DraftStore.save + RecommendJobStore.markFinished)을 재사용한 뒤 XDEL,
 * 폐기는 XDEL 만 수행한다.
 *
 * DLQ 스트림 키/필드는 RecommendJobsConsumer.routeToDlq 규약과 일치한다:
 *   job_id / status / payload / original_id / delivery_count / error
 */
@Service
public class AdminDlqService {

    private static final Logger log = LoggerFactory.getLogger(AdminDlqService.class);
    private static final int PREVIEW_LEN = 200;

    private final StringRedisTemplate streamsTemplate;
    private final DraftStore draftStore;
    private final RecommendJobStore jobStore;
    private final String dlqStream;
    private final PayloadCipher payloadCipher;

    public AdminDlqService(
            @Qualifier("streamsConnectionFactory") RedisConnectionFactory streamsFactory,
            DraftStore draftStore,
            RecommendJobStore jobStore,
            @Value("${streams.recommend-dlq-stream:agent:jobs:done:dlq}") String dlqStream,
            PayloadCipher payloadCipher) {
        this.streamsTemplate = new StringRedisTemplate(streamsFactory);
        this.draftStore = draftStore;
        this.jobStore = jobStore;
        this.dlqStream = dlqStream;
        this.payloadCipher = payloadCipher;
    }

    /** DLQ 최근 항목을 limit 개까지(최신순) 조회한다. */
    public List<DlqEntry> list(int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        List<MapRecord<String, Object, Object>> records = streamsTemplate.opsForStream()
                .reverseRange(dlqStream, Range.unbounded(), Limit.limit().count(capped));
        List<DlqEntry> out = new ArrayList<>();
        if (records == null) {
            return out;
        }
        for (MapRecord<String, Object, Object> rec : records) {
            Map<Object, Object> v = rec.getValue();
            out.add(new DlqEntry(
                    rec.getId().getValue(),
                    str(v.get("job_id")),
                    str(v.get("status")),
                    str(v.get("delivery_count")),
                    str(v.get("error")),
                    preview(str(v.get("payload")))));
        }
        return out;
    }

    /**
     * DLQ 항목을 재처리한다: 각 recordId 의 payload 로 draft 저장 + 작업 완료
     * 기록 후 DLQ 에서 삭제. 항목이 없거나 처리 실패면 failedIds 에 담는다.
     */
    public DlqActionResult reprocess(List<String> ids) {
        int succeeded = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                Map<Object, Object> v = readById(id);
                if (v == null) {
                    failed.add(id);
                    continue;
                }
                String jobId = str(v.get("job_id"));
                String payload = payloadCipher.decrypt(
                        str(v.get("payload")),
                        PayloadCipher.aad("redis", "agent:jobs:done:dlq", jobId));
                String status = str(v.get("status"));
                if (jobId == null || payload == null || payload.isBlank()) {
                    failed.add(id);
                    continue;
                }
                draftStore.save(jobId, payload);
                jobStore.markFinished(jobId, status, payload);
                streamsTemplate.opsForStream().delete(dlqStream, id);
                succeeded++;
            } catch (RuntimeException e) {
                log.warn("dlq reprocess failed id={} reason={}", id, e.getMessage());
                failed.add(id);
            }
        }
        return new DlqActionResult(ids.size(), succeeded, failed.size(), failed);
    }

    /** DLQ 항목을 재처리 없이 폐기(XDEL)한다. */
    public DlqActionResult discard(List<String> ids) {
        int succeeded = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                Long deleted = streamsTemplate.opsForStream().delete(dlqStream, id);
                if (deleted != null && deleted > 0) {
                    succeeded++;
                } else {
                    failed.add(id);
                }
            } catch (RuntimeException e) {
                log.warn("dlq discard failed id={} reason={}", id, e.getMessage());
                failed.add(id);
            }
        }
        return new DlqActionResult(ids.size(), succeeded, failed.size(), failed);
    }

    /** 특정 recordId 의 DLQ 엔트리를 읽는다(없으면 null). */
    private Map<Object, Object> readById(String id) {
        List<MapRecord<String, Object, Object>> recs = streamsTemplate.opsForStream()
                .range(dlqStream, Range.closed(id, id));
        if (recs == null || recs.isEmpty()) {
            return null;
        }
        return recs.get(0).getValue();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String preview(String payload) {
        if (payload == null) {
            return null;
        }
        return payload.length() <= PREVIEW_LEN
                ? payload
                : payload.substring(0, PREVIEW_LEN) + "…";
    }
}
