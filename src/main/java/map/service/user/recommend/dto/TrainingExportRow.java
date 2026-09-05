package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 학습 자료 한 줄 = 한 세션.
 *
 * <p>한 세션은 "이 조건으로 물었고, 이런 후보가 나왔고, 그중 이것을 저장했고,
 * 실제로 이만큼 갔다" 를 한 덩어리로 묶은 것이다. 학습에서 입력은 요청 조건과
 * 성향과 후보 목록이고, 정답은 {@code candidates[].saved} 다.
 *
 * <p>{@code chosen} 을 정답으로 쓰지 않는 이유: 그것은 모델이 고른 것이지
 * 사람이 고른 것이 아니다. 그것으로 배우면 지금 모델을 베끼는 데 그친다.
 * 진단용으로 함께 싣되 정답 자리에는 두지 않는다.
 *
 * <p>{@code rank} 를 함께 싣는 이유: 고르지 않은 후보를 그대로 반례로 쓰면
 * 위에 있어서 뽑히기 쉬웠던 효과가 선호로 둔갑한다. 보정하려면 그때의 순위가
 * 필요하다.
 *
 * <p>DB 의 JSON 을 그대로 옮기는 칸({@code request}·{@code rankingConfig}·
 * {@code userSegment})은 다시 뜯지 않는다. 뜯어 옮기면 원본이 바뀔 때마다 여기도
 * 함께 고쳐야 하는데, 이 파일은 만들어 쓰고 버리는 파생물이라 그럴 값이 없다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TrainingExportRow(
        @JsonProperty("export_schema_version") int exportSchemaVersion,
        @JsonProperty("exported_at") String exportedAt,

        @JsonProperty("session_id") Long sessionId,
        /** 원 식별자가 아니라 소금 섞은 지문. 파일만으로는 누구인지 되돌릴 수 없다. */
        @JsonProperty("user_ref") String userRef,
        @JsonProperty("user_known") boolean userKnown,

        @JsonProperty("saved_job_id") String savedJobId,
        /** 후보와 선택 근거를 실제로 가진 잡. 캐시로 답한 세션은 이것이 다르다. */
        @JsonProperty("origin_job_id") String originJobId,
        /** self = 저장한 잡이 곧 원본, parent = 계보를 한 칸 따라갔다. */
        @JsonProperty("origin_resolution") String originResolution,

        /** 사용자가 한 행동. */
        @JsonProperty("mode") String mode,
        @JsonProperty("source") String source,
        /** 후보가 나온 자리. 사용자 행동과 다를 수 있다(캐시·백그라운드 갱신). */
        @JsonProperty("origin_mode") String originMode,
        @JsonProperty("origin_source") String originSource,
        @JsonProperty("training_schema_version") Integer trainingSchemaVersion,

        /**
         * 나누기 기준. 같은 조건으로 물은 세션은 한쪽으로 몰아야 한다.
         *
         * <p>잡 단위로 나누면 새지 않을 것 같지만 샌다 — 뒤에서 같은 조건으로
         * 캐시를 다시 채우면서 새 잡이 계속 생기기 때문에, 사실상 같은 세션이
         * 배우는 쪽과 재는 쪽으로 갈라진다.
         */
        @JsonProperty("split_key") String splitKey,
        @JsonProperty("split_key_source") String splitKeySource,

        @JsonProperty("request") JsonNode request,
        @JsonProperty("ranking_config") JsonNode rankingConfig,
        @JsonProperty("user_segment") JsonNode userSegment,

        @JsonProperty("schedule") Schedule schedule,
        @JsonProperty("candidates") List<Candidate> candidates,
        @JsonProperty("labels") Labels labels,
        /** 일정 주변에서 보여 준 장소와 그중 눌린 것. 없으면 빈 목록. */
        @JsonProperty("nearby") List<NearbyImpression> nearby,
        @JsonProperty("counts") Counts counts,

        /** 후보 랭킹 학습에 쓸 수 있는 세션인지. */
        @JsonProperty("l1_eligible") boolean l1Eligible,
        @JsonProperty("l1_exclusion_reason") String l1ExclusionReason
) {

    /** 저장된 일정 쪽 사실. */
    public record Schedule(
            @JsonProperty("date_start") String dateStart,
            @JsonProperty("date_end") String dateEnd,
            @JsonProperty("transport") String transport,
            @JsonProperty("active_start_hour") Integer activeStartHour,
            @JsonProperty("active_end_hour") Integer activeEndHour,
            @JsonProperty("saved_at") String savedAt,
            /** 따라가기를 누른 시각. 누른 적 없으면 비어 있다. */
            @JsonProperty("started_at") String startedAt,
            /** 같은 것을 여러 번 저장했으면 몇 벌인지. */
            @JsonProperty("duplicate_count") int duplicateCount,
            /** 여러 벌 중 하나라도 지웠는지(전부 지운 것과 구분한다). */
            @JsonProperty("any_deleted") boolean anyDeleted
    ) {
    }

    /** 모델에 실렸던 후보 하나와 그 뒤에 벌어진 일. */
    public record Candidate(
            @JsonProperty("content_id") String contentId,
            /** 모델에게 보인 순서. 노출 편향을 보정하는 데 쓴다. */
            @JsonProperty("rank") Integer rank,
            @JsonProperty("name") String name,
            @JsonProperty("category") String category,
            @JsonProperty("category_group_code") String categoryGroupCode,
            @JsonProperty("lat") Double lat,
            @JsonProperty("lng") Double lng,
            /** 룰 엔진 점수. 사람의 선호가 아니라 기존 규칙의 산출값이다. */
            @JsonProperty("score_pre_cap") Double scorePreCap,
            /** 모델이 골랐는지. 진단용. */
            @JsonProperty("chosen") boolean chosen,
            /** 사용자가 저장했는지. <b>이것이 정답이다.</b> */
            @JsonProperty("saved") boolean saved,
            /** 실제로 그 자리에 닿았는지. */
            @JsonProperty("arrived") boolean arrived
    ) {
    }

    /** 세션 단위 라벨. */
    public record Labels(
            @JsonProperty("saved_content_ids") List<String> savedContentIds,
            @JsonProperty("chosen_content_ids") List<String> chosenContentIds,
            /** 다시 짜기로 통째로 물린 앞의 결과. */
            @JsonProperty("rejected_content_ids") List<String> rejectedContentIds,
            @JsonProperty("rejected_source") String rejectedSource,
            @JsonProperty("arrived_content_ids") List<String> arrivedContentIds,
            /** 저장했다가 지웠는지. 여러 벌이면 전부 지웠을 때만 참이다. */
            @JsonProperty("deleted") boolean deleted
    ) {
    }

    /**
     * 주변 장소를 한 건 보여 준 것과 그것이 눌렸는지.
     *
     * <p>보여 준 것을 함께 싣는 이유: 눌린 것만 있으면 "안 눌렀다" 가
     * "안 보였다" 인지 "보고 안 골랐다" 인지 구분되지 않아 반례로 못 쓴다.
     */
    public record NearbyImpression(
            @JsonProperty("day") Integer day,
            @JsonProperty("stop_order") Integer stopOrder,
            @JsonProperty("category") String category,
            @JsonProperty("content_id") String contentId,
            /** 목록에서 몇 번째로 보였는지. 노출 편향 보정용. */
            @JsonProperty("rank") Integer rank,
            @JsonProperty("clicked") boolean clicked
    ) {
    }

    public record Counts(
            @JsonProperty("candidates") int candidates,
            @JsonProperty("saved") int saved,
            @JsonProperty("chosen") int chosen,
            @JsonProperty("arrived") int arrived,
            @JsonProperty("nearby_shown") int nearbyShown,
            @JsonProperty("nearby_clicked") int nearbyClicked
    ) {
    }
}
