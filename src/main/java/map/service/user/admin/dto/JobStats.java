package map.service.user.admin.dto;

import java.util.Map;

/**
 * JobStats — 추천 작업 상태 통계
 *
 * byStatus       : 상태별(in_progress/done/failed) 건수 맵.
 * total          : 전체 건수.
 * failedLast24h  : 최근 24시간 내 생성된 실패 건수.
 */
public record JobStats(
        Map<String, Long> byStatus,
        long total,
        long failedLast24h
) {
}
